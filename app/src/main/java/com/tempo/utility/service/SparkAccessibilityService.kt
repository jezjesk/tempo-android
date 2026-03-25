package com.tempo.utility.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioAttributes
  import android.media.AudioManager
  import android.media.RingtoneManager
import kotlin.random.Random
  import android.os.Build
  import android.os.Handler
  import android.os.HandlerThread
  import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tempo.utility.logging.CsvLogger
import com.tempo.utility.logging.SparkLogger
import com.tempo.utility.settings.AppSettings

/**
 * SparkAccessibilityService
 *
 * Monitors the Spark Driver app (com.walmart.sparkdriver) for delivery offer cards.
 *
 * State machine:
 *   IDLE              → HOME_OFFER: tap card → TAPPING_OFFER_CARD
 *   TAPPING_OFFER_CARD → DETAIL: scrape → ACCEPTING or REJECTING
 *   ACCEPTING          → postDelayed accept tap → CONFIRMING_ACCEPT
 *   CONFIRMING_ACCEPT  → Start Trip: monitoring paused; Got It: IDLE
 *   REJECTING          → retry reject button every 3 s; confirmation/expiry → IDLE
 *
 * Every action lives on the screen that triggered it — no cross-screen navigation.
 * Screen detection uses resource-IDs (not text) to avoid fragile string matching.
 */
class SparkAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilitySvc"

        /** Confirmed package name from XML hierarchy dumps */
        const val SPARK_PACKAGE = "com.walmart.sparkdriver"

        /** Action broadcast to MainActivity for live status updates */
        const val ACTION_STATUS_UPDATE = "com.tempo.utility.STATUS_UPDATE"
        const val EXTRA_STATUS_MESSAGE = "status_message"

        /** Action sent FROM MainActivity to pause/resume monitoring */
        const val ACTION_SET_MONITORING = "com.tempo.utility.SET_MONITORING"
        const val EXTRA_MONITORING_ENABLED = "monitoring_enabled"

        /** In-process flag — flipped by the broadcast receiver below */
        @Volatile var isMonitoring = true

        // Resource IDs — from actual UI hierarchy XML files
        // Home screen
        private const val ID_HOME_LAYOUT         = "$SPARK_PACKAGE:id/activityHomeWithMapLayout"
        private const val ID_BOTTOM_SHEET        = "$SPARK_PACKAGE:id/bottom_sheet_home_with_map"
        private const val ID_RECYCLER            = "$SPARK_PACKAGE:id/homeBottomSheetRecyclerView"
        private const val ID_SEARCHING           = "$SPARK_PACKAGE:id/tvSearchOffers"
        // Detail screen — confirmed from log dump 2026-03-24
        private const val ID_ESTIMATED_LABEL     = "$SPARK_PACKAGE:id/estimatedLabelView"
        private const val ID_ESTIMATED_VALUE     = "$SPARK_PACKAGE:id/estimatedValueView"
        private const val ID_TIPS_VIEW           = "$SPARK_PACKAGE:id/tipsView"
        private const val ID_TRIP_SUMMARY        = "$SPARK_PACKAGE:id/tripSummary"
        private const val ID_STOP_COUNT          = "$SPARK_PACKAGE:id/stopCount"
        private const val ID_DISTANCE            = "$SPARK_PACKAGE:id/distance"
        private const val ID_TIME                = "$SPARK_PACKAGE:id/time"
        private const val ID_VALUE_VIEW          = "$SPARK_PACKAGE:id/valueView"
        private const val ID_LABEL_VIEW          = "$SPARK_PACKAGE:id/labelView"
        private const val ID_TAG_TEXT            = "$SPARK_PACKAGE:id/tagText"
        // Action buttons on detail screen — IDs are unconfirmed; text-based fallback is active
        private const val ID_REJECT_BUTTON_DETAIL  = "$SPARK_PACKAGE:id/rejectButton"
        private const val ID_ACCEPT_BUTTON_DETAIL  = "$SPARK_PACKAGE:id/acceptButton"    // on detail screen
        // Detail screen — additional fields confirmed from log dump 2026-03-24
        private const val ID_SUB_TITLE           = "$SPARK_PACKAGE:id/subTitle"          // "X items (Y qty)" at pickup
        private const val ID_TITLE               = "$SPARK_PACKAGE:id/title"             // stop type labels
        private const val ID_ADDRESS_NAME        = "$SPARK_PACKAGE:id/address_name"      // store / customer name
        private const val ID_CHIP                = "$SPARK_PACKAGE:id/chip"              // offer tags (Pharmacy, Shopping…)
        // "Offer unavailable" dialog — confirmed from log dump 2026-03-24
        private const val ID_GOT_IT_BUTTON          = "$SPARK_PACKAGE:id/gotItButton"
        // Rejection confirmation bottom sheet — confirmed from log dump 2026-03-24
        private const val ID_REJECT_CONFIRM_BUTTON  = "$SPARK_PACKAGE:id/rejectThisOfferButton"
        private const val ID_KEEP_OFFER_BUTTON      = "$SPARK_PACKAGE:id/keepThisOfferButton"
        // Active trip home card — ctaButton ("START TRIP") replaces accept/reject buttons
        private const val ID_REJECTION_BUTTON_HOME = "$SPARK_PACKAGE:id/rejectionButton"
        private const val ID_CTA_BUTTON            = "$SPARK_PACKAGE:id/ctaButton"
        // Active trip detail screen — appear once a trip is confirmed (replace accept/reject)
        private const val ID_START_TRIP_BUTTON     = "$SPARK_PACKAGE:id/startTripRegularButton"
        private const val ID_CANCEL_TRIP_BUTTON    = "$SPARK_PACKAGE:id/cancelTripButton"

        // Confirmed (2026-03-24): "Just for you" appears as a title node on the detail screen.
        val JFY_KEYWORDS = listOf("just for you", "reserved for you", "exclusive")

        private const val TAP_DEBOUNCE_MS           = 4_000L
        private const val CONFIRM_ACCEPT_TIMEOUT   = 20_000L
        private const val DEDUP_WINDOW_MS          = 5 * 60 * 1_000L  // 5 min
        private const val TAPPING_CARD_TIMEOUT_MS  = 15_000L    // give up on detail opening after 15 s
        private const val AUTO_REJECT_TIMEOUT_MS   = 30_000L    // give up on reject flow after 30 s
        private const val REJECT_DETAIL_RETRY_MS   = 3_000L     // retry reject/back every 3 s on detail

        // Start Trip screen — detected by text since IDs are unconfirmed
        private val START_TRIP_TEXTS = listOf("start trip", "start your trip", "begin trip")
    }

    private enum class AppScreen { HOME_SEARCHING, HOME_OFFER, DETAIL, OTHER }

    /**
     * State machine states.
     *
     * Each state owns exactly the screens it acts on:
     *   IDLE              → HOME_OFFER: tap card
     *   TAPPING_OFFER_CARD → DETAIL: scrape → ACCEPTING or REJECTING
     *   ACCEPTING          → DETAIL: random-delay accept tap; OTHER/HOME_SEARCHING: expiry handling
     *   CONFIRMING_ACCEPT  → OTHER: Start Trip or Got It; HOME_SEARCHING: expiry
     *   REJECTING          → DETAIL: retry reject button every 3 s; OTHER: confirm/expiry modals
     *
     * No navigation away from the detail screen ever occurs.  Rejection is resolved
     * entirely by tapping the reject button on the detail screen.
     */
    private enum class State { IDLE, TAPPING_OFFER_CARD, ACCEPTING, CONFIRMING_ACCEPT, REJECTING }

    @Volatile private var state = State.IDLE
    private val mainHandler = Handler(Looper.getMainLooper())

    // Background HandlerThread for action-tap delays.
    // Samsung Android 15 battery optimiser aggressively throttles postDelayed on the
    // main-thread Handler (observed 27–40 s delays for 200–600 ms requests).
    // A background HandlerThread bypasses that throttle while still letting us
    // schedule human-like random delays before tapping Accept / Reject.
    // State mutations inside the lambda are always posted back to mainHandler.
    private val tapHandlerThread = HandlerThread("AaaDisTapThread")
    private lateinit var tapHandler: Handler

    private val monitoringReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val enabled = intent?.getBooleanExtra(EXTRA_MONITORING_ENABLED, true) ?: true
            isMonitoring = enabled
            SparkLogger.i(TAG, "monitoringReceiver: isMonitoring=$enabled")
            if (!enabled) {
                state = State.IDLE
                broadcastStatus("Monitoring paused")
            } else {
                broadcastStatus("Monitoring resumed — watching for offers")
            }
        }
    }

    private var lastTapTime        = 0L
    private var confirmAcceptTimeoutRunnable: Runnable? = null
    private var acceptingTimeoutRunnable: Runnable? = null
    private var tappingCardTimeoutRunnable: Runnable? = null
    private var autoRejectTimeoutRunnable: Runnable? = null
    /** Sentinel Long.MAX_VALUE = initial delay not yet fired; else = timestamp of last reject attempt.
     *  Written from tapHandler (background thread), read from mainHandler — must be @Volatile. */
    @Volatile private var lastDetailRejectAttemptTime = Long.MAX_VALUE
    @Volatile private var lastGotItTapMs = 0L
    private var lastRejectConfirmTapMs = 0L

    // Offer deduplication — skip CSV write if we see the same offer within DEDUP_WINDOW_MS
    private data class OfferKey(val sparkPay: Double, val tip: Double, val timeMin: Double)
    private var lastRecordedKey     : OfferKey? = null
    private var lastRecordedTime    : Long      = 0L
    private var lastOfferCsvWritten : Boolean   = false

    // Deduplicated / throttled logging
    private val seenPackages = mutableSetOf<String>()
    private var suppressedCount = 0L
    private var lastSuppressLog = 0L
    private var lastLoggedScreen: AppScreen? = null   // only log detectScreen on change
    private var lastHasOffer = false                   // only log hasOfferInRecycler on change
    private var lastWaitingLogTime = 0L                // throttle "still waiting" to 1× per 15 s
    private var lastNullWindowLogTime = 0L             // throttle null-window warnings to 1× per 5 s
    private var scrapeRetries = 0                      // guard against infinite retry loops
    private val MAX_SCRAPE_RETRIES = 12

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        tapHandlerThread.start()
        tapHandler = Handler(tapHandlerThread.looper)
        SparkLogger.i(TAG, "=== Tempo Accessibility Service connected ===")
        SparkLogger.i(TAG, "Monitoring package: $SPARK_PACKAGE")
        val filter = IntentFilter(ACTION_SET_MONITORING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(monitoringReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(monitoringReceiver, filter)
        }
        broadcastStatus("Tempo connected — open Spark Driver to begin monitoring")
    }

    override fun onInterrupt() {
        SparkLogger.w(TAG, "onInterrupt — service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        SparkLogger.i(TAG, "onDestroy — service stopping")
        mainHandler.removeCallbacksAndMessages(null)
        tapHandlerThread.quitSafely()
        tappingCardTimeoutRunnable = null
        acceptingTimeoutRunnable = null
        autoRejectTimeoutRunnable = null
        confirmAcceptTimeoutRunnable = null
        try { unregisterReceiver(monitoringReceiver) } catch (_: IllegalArgumentException) { }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Main event dispatch
    // ──────────────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isMonitoring) return
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Log every new package once — useful for debugging
        if (seenPackages.add(pkg)) {
            SparkLogger.i(TAG, "NEW PACKAGE SEEN: $pkg")
        }

        if (pkg != SPARK_PACKAGE) {
            suppressedCount++
            val now = System.currentTimeMillis()
            if (now - lastSuppressLog > 15_000) {
                SparkLogger.v(TAG, "Suppressed $suppressedCount non-Spark events")
                lastSuppressLog = now
                suppressedCount = 0
            }
            return
        }

        // Only log event types that signal a meaningful UI change (not every scroll/content tick)
        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
            SparkLogger.d(TAG, "Spark event: ${AccessibilityEvent.eventTypeToString(eventType)} | class=${event.className}")
        }

        val root = rootInActiveWindow
        if (root == null) {
            val now = System.currentTimeMillis()
            if (now - lastNullWindowLogTime > 5_000) {
                SparkLogger.d(TAG, "rootInActiveWindow is null — window transitioning")
                lastNullWindowLogTime = now
            }
            return
        }

        try {
            handleEvent(root)
        } finally {
            root.recycle()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // State machine dispatcher
    // ──────────────────────────────────────────────────────────────────────────

    private fun handleEvent(root: AccessibilityNodeInfo) {
        val screen = detectScreen(root)

        // Only log screen changes — suppresses the flood of identical "HOME_SEARCHING | IDLE" lines
        if (screen != lastLoggedScreen) {
            val prevScreen = lastLoggedScreen
            SparkLogger.i(TAG, "Screen changed: $prevScreen → $screen | state=$state")
            lastLoggedScreen = screen

            // Dump every screen we don't specifically recognise so unhandled screens
            // are captured in full in the log and can be diagnosed from the share.
            if (screen == AppScreen.OTHER) {
                dumpScreenIds(root, "UNKNOWN_SCREEN (prev=$prevScreen state=$state)")
            }
        }

        when (state) {

            // ──────────────────────────────────────────────────────────────────
            // IDLE — watching for offers
            // ──────────────────────────────────────────────────────────────────
            State.IDLE -> {
                when (screen) {
                    AppScreen.HOME_OFFER -> handleOfferOnHomeScreen(root)
                    AppScreen.DETAIL -> {
                        // Safety net: DETAIL appeared while IDLE (e.g. state was reset during the
                        // brief HOME_SEARCHING flash that can occur between tap and DETAIL load).
                        cancelTappingCardTimeout()
                        SparkLogger.i(TAG, "IDLE: DETAIL screen — scraping as safety net")
                        scrapeAndRecord(root)
                    }
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastWaitingLogTime > 15_000) {
                            SparkLogger.d(TAG, "IDLE: screen=$screen — watching for offers…")
                            lastWaitingLogTime = now
                        }
                    }
                }
            }

            // ──────────────────────────────────────────────────────────────────
            // TAPPING_OFFER_CARD — tapped home card, waiting for DETAIL to load
            //   [15-second safety timeout resets to IDLE if DETAIL never appears]
            // ──────────────────────────────────────────────────────────────────
            State.TAPPING_OFFER_CARD -> {
                when (screen) {
                    AppScreen.DETAIL -> {
                        // Cancel the stuck-state timeout; scrapeAndRecord sets the next state
                        // (ACCEPTING or REJECTING) once data is ready, or stays in
                        // TAPPING_OFFER_CARD if the screen is still loading (auto-retries on
                        // the next accessibility event).
                        // Guard: if this is an active trip detail screen (trip already accepted),
                        // the buttons are startTripRegularButton / cancelTripButton, NOT
                        // acceptButton / rejectButton.  Pause monitoring and reset.
                        if (hasNodeWithId(root, ID_START_TRIP_BUTTON) ||
                            hasNodeWithId(root, ID_CANCEL_TRIP_BUTTON)) {
                            SparkLogger.i(TAG, "TAPPING_OFFER_CARD: active trip detail — trip already accepted, pausing monitoring")
                            cancelTappingCardTimeout()
                            isMonitoring = false
                            state = State.IDLE
                            broadcastStatus("Active trip — monitoring paused. Resume in the app.")
                            return
                        }
                        cancelTappingCardTimeout()
                        SparkLogger.i(TAG, "TAPPING_OFFER_CARD: DETAIL screen — scraping offer")
                        scrapeAndRecord(root)
                    }
                    AppScreen.HOME_SEARCHING -> {
                        // Brief HOME_SEARCHING flash that appears during HOME → DETAIL navigation.
                        // Do NOT reset state; DETAIL will follow within ~300 ms.
                        val now = System.currentTimeMillis()
                        if (now - lastWaitingLogTime > 5_000) {
                            SparkLogger.d(TAG, "TAPPING_OFFER_CARD: HOME_SEARCHING flash — waiting for DETAIL")
                            lastWaitingLogTime = now
                        }
                    }
                    else -> { /* transitioning — stay */ }
                }
            }

            // ──────────────────────────────────────────────────────────────────
            // ACCEPTING — criteria met, random-delay accept tap is queued
            //   Action stays entirely on the DETAIL screen.
            //   If the offer expires before the tap fires, the expiry modal (OTHER)
            //   or HOME_SEARCHING signals that we should give up and go IDLE.
            // ──────────────────────────────────────────────────────────────────
            State.ACCEPTING -> {
                when (screen) {
                    AppScreen.OTHER -> {
                        // "Offer unavailable" dialog appeared before we could tap accept
                        val gotIt = findNodeById(root, ID_GOT_IT_BUTTON)
                            ?: findClickableByText(root, listOf("got it"))
                        if (gotIt != null) {
                            SparkLogger.i(TAG, "ACCEPTING: offer-unavailable dialog — tapping Got It, resetting IDLE")
                              if (lastOfferCsvWritten) CsvLogger.updateLastActionResult("ACCEPT_UNAVAILABLE")
                            tapGotItWithRetry(gotIt)
                            gotIt.recycle()
                            cancelAcceptingTimeout()
                            state = State.IDLE
                            broadcastStatus("Offer unavailable — watching for next offer")
                        }
                        // else: some other transient overlay; wait
                    }
                    AppScreen.HOME_SEARCHING -> {
                        // Offer expired before the accept tap fired
                        cancelAcceptingTimeout()
                        if (lastOfferCsvWritten) CsvLogger.updateLastActionResult("ACCEPT_EXPIRED")
                          SparkLogger.i(TAG, "ACCEPTING: offer expired — resetting IDLE")
                        state = State.IDLE
                        broadcastStatus("Offer expired — watching for next offer")
                    }
                    AppScreen.DETAIL -> {
                        // If accept already succeeded, the DETAIL screen now shows the active
                        // trip (startTripRegularButton / cancelTripButton).  Pause monitoring.
                        if (hasNodeWithId(root, ID_START_TRIP_BUTTON) ||
                            hasNodeWithId(root, ID_CANCEL_TRIP_BUTTON)) {
                            if (lastOfferCsvWritten) CsvLogger.updateLastActionResult("ACCEPTED")
                              playHorn()
                              SparkLogger.i(TAG, "ACCEPTING: active trip detail detected — trip already accepted, pausing monitoring")
                            cancelAcceptingTimeout()
                            isMonitoring = false
                            state = State.IDLE
                            broadcastStatus("Trip accepted! Monitoring paused — resume in the app.")
                        }
                        // else: still loading, waiting for the postDelayed accept tap
                    }
                    else -> { /* HOME_OFFER or other transient screen — stay ACCEPTING */ }
                }
            }

            // ──────────────────────────────────────────────────────────────────
            // CONFIRMING_ACCEPT — accept was tapped; watching for outcome
            //   • Start Trip screen → trip confirmed, pause monitoring
            //   • "Offer unavailable" modal → accept failed, resume monitoring
            //   [20-second timeout resets to IDLE if neither appears]
            // ──────────────────────────────────────────────────────────────────
            State.CONFIRMING_ACCEPT -> {
                handleConfirmingAccept(root, screen)
            }

            // ──────────────────────────────────────────────────────────────────
            // REJECTING — criteria failed; retrying the reject button on DETAIL
            //   every REJECT_DETAIL_RETRY_MS until:
            //   • confirmation sheet (OTHER) → tap rejectThisOfferButton
            //   • expiry modal (OTHER gotItButton) → tap Got It → IDLE
            //   • offer disappears (HOME_SEARCHING) → IDLE
            //   [30-second safety timeout resets to IDLE if stuck]
            //   NEVER navigates away from the detail screen.
            // ──────────────────────────────────────────────────────────────────
            State.REJECTING -> {
                when (screen) {
                    AppScreen.DETAIL -> {
                        // Retry the reject button on every event, throttled to REJECT_DETAIL_RETRY_MS.
                        // Long.MAX_VALUE sentinel: initial random delay has not yet fired.
                        val now = System.currentTimeMillis()
                        if (lastDetailRejectAttemptTime != Long.MAX_VALUE &&
                            now - lastDetailRejectAttemptTime >= REJECT_DETAIL_RETRY_MS) {
                            lastDetailRejectAttemptTime = now
                            SparkLogger.i(TAG, "REJECTING: DETAIL — retrying reject button")
                            val tapped = tryDeclineOnDetailScreen(root)
                            if (!tapped) {
                                // Button not found yet — dump the live tree so we can identify the ID
                                SparkLogger.w(TAG, "REJECTING: reject button not found — will retry in ${REJECT_DETAIL_RETRY_MS / 1000}s")
                                dumpScreenIds(root, "REJECTING/DETAIL/NO_BUTTON_FOUND")
                            }
                        }
                    }
                    AppScreen.OTHER -> {
                        // Priority 1: rejection confirmation sheet (rejectThisOfferButton)
                        val rejectConfirm = findNodeById(root, ID_REJECT_CONFIRM_BUTTON)
                        if (rejectConfirm != null) {
                            val nowRc = System.currentTimeMillis()
                            if (nowRc - lastRejectConfirmTapMs > 600L) {
                                lastRejectConfirmTapMs = nowRc
                                tapNode(rejectConfirm)
                                SparkLogger.i(TAG, "REJECTING: confirmation sheet — gesture tap on rejectThisOfferButton")
                            } else {
                                SparkLogger.d(TAG, "REJECTING: rejectThisOfferButton debounced (${nowRc - lastRejectConfirmTapMs}ms)")
                            }
                            rejectConfirm.recycle()
                            broadcastStatus("Confirming rejection…")
                            // Stay REJECTING — HOME_SEARCHING will confirm the offer is gone
                            return
                        }
                        // Priority 2: "Offer unavailable" / expiry modal
                        val gotIt = findNodeById(root, ID_GOT_IT_BUTTON)
                            ?: findClickableByText(root, listOf("got it"))
                        if (gotIt != null) {
                            if (lastOfferCsvWritten) CsvLogger.updateLastActionResult("REJECT_EXPIRED")
                              SparkLogger.i(TAG, "REJECTING: offer-unavailable dialog — tapping Got It, resetting IDLE")
                            tapGotItWithRetry(gotIt)
                            gotIt.recycle()
                            cancelAutoRejectTimeout()
                            state = State.IDLE
                            broadcastStatus("Offer unavailable — watching for next offer")
                            return
                        }
                        // Unknown OTHER screen — dump for diagnosis
                        SparkLogger.w(TAG, "REJECTING: unrecognised OTHER screen — staying REJECTING")
                    }
                    AppScreen.HOME_SEARCHING -> {
                        // Offer is gone — either rejected successfully or expired
                        cancelAutoRejectTimeout()
                        if (lastOfferCsvWritten) CsvLogger.updateLastActionResult("REJECTED")
                          SparkLogger.i(TAG, "REJECTING: HOME_SEARCHING — offer gone, resetting IDLE")
                        state = State.IDLE
                        broadcastStatus("Offer rejected — watching for next offer")
                    }
                    AppScreen.HOME_OFFER -> {
                        // Shouldn't occur since we never navigate away from DETAIL during rejection,
                        // but handle it gracefully so we don't get stuck.
                        SparkLogger.w(TAG, "REJECTING: unexpected HOME_OFFER — resetting IDLE (timeout will not be needed)")
                        cancelAutoRejectTimeout()
                        state = State.IDLE
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Screen detection — uses resource IDs (reliable, not text-fragile)
    // ──────────────────────────────────────────────────────────────────────────

    private fun detectScreen(root: AccessibilityNodeInfo): AppScreen {
        // Detail screen: has the "Estimated total" label unique to detail view
        if (hasNodeWithId(root, ID_ESTIMATED_LABEL) || hasNodeWithId(root, ID_TIPS_VIEW)) {
            return AppScreen.DETAIL
        }

        // Home screen: has the home layout
        if (hasNodeWithId(root, ID_HOME_LAYOUT) || hasNodeWithId(root, ID_BOTTOM_SHEET)) {
            // Check if there's an actual offer (has "estimate" text in bottom sheet)
            // vs just the "Searching for offers" waiting state
            val recycler = findNodeById(root, ID_RECYCLER)
            val searchingNode = findNodeById(root, ID_SEARCHING)

            return if (recycler != null && hasOfferInRecycler(recycler)) {
                recycler.recycle()
                searchingNode?.recycle()
                AppScreen.HOME_OFFER
            } else {
                recycler?.recycle()
                searchingNode?.recycle()
                AppScreen.HOME_SEARCHING
            }
        }

        return AppScreen.OTHER
    }

    /**
     * Checks whether the RecyclerView in the bottom sheet contains an offer card.
     * An offer card contains text matching the "$XX.XX estimate" pattern.
     */
    private fun hasOfferInRecycler(recycler: AccessibilityNodeInfo): Boolean {
        val texts = mutableListOf<String>()
        collectAllText(recycler, texts)
        val hasEstimate = texts.any { it.contains("estimate", ignoreCase = true) }
        val hasDollar   = texts.any { it.matches(Regex("\\$[0-9]+\\.?[0-9]*.*")) }
        val result = hasEstimate && hasDollar
        // Only log when the offer-present state changes, not on every event
        if (result != lastHasOffer) {
            SparkLogger.i(TAG, "hasOfferInRecycler changed → $result | estimate=$hasEstimate dollar=$hasDollar texts=${texts.take(6)}")
            lastHasOffer = result
        }
        return result
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HOME_OFFER: tap the offer card to open detail screen
    // ──────────────────────────────────────────────────────────────────────────

    private fun handleOfferOnHomeScreen(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastTapTime < TAP_DEBOUNCE_MS) {
            val remaining = TAP_DEBOUNCE_MS - (now - lastTapTime)
            SparkLogger.d(TAG, "Offer found but debouncing — ${remaining}ms left")
            return
        }

        SparkLogger.i(TAG, "HOME_OFFER: offer card detected — finding tap target")
        dumpScreenIds(root, "HOME_OFFER")

        // Guard: an accepted trip card looks identical to an offer card (same $XX.XX estimate
        // text and clickableLayout) but has ctaButton ("START TRIP") instead of rejectionButton
        // + acceptanceButton.  Tapping it would open the active trip detail, confusing state.
        val hasRejectBtn = findNodeById(root, ID_REJECTION_BUTTON_HOME)
        if (hasRejectBtn != null) hasRejectBtn.recycle()
        if (hasRejectBtn == null) {
            val ctaNode = findNodeById(root, ID_CTA_BUTTON)
            val hasStartTrip = ctaNode != null
                || findClickableByText(root, listOf("start trip")) != null
            ctaNode?.recycle()
            if (hasStartTrip) {
                SparkLogger.i(TAG, "HOME_OFFER: active trip card detected (no reject/accept buttons, START TRIP present) — pausing monitoring")
                isMonitoring = false
                state = State.IDLE
                broadcastStatus("Active trip — monitoring paused. Resume in the app.")
                return
            }
        }

        // Strategy: find the node with "estimate" text, walk up to its clickable ancestor
        val tapTarget = findOfferCardTapTarget(root)
        if (tapTarget == null) {
            SparkLogger.w(TAG, "HOME_OFFER: could not find tap target on offer card")
            return
        }

        state = State.TAPPING_OFFER_CARD
        lastTapTime = now
        scheduleTappingCardTimeout()   // escape hatch if detail screen never appears
        // Tap the offer card using a randomised gesture — no ACTION_CLICK.
        SparkLogger.i(TAG, "HOME_OFFER: tapping offer card via gesture")
        broadcastStatus("Offer detected — tapping card…")
        val tapped = tapNode(tapTarget)
        SparkLogger.i(TAG, "HOME_OFFER: gesture tap dispatched=$tapped")
        tapTarget.recycle()
        // Stay in TAPPING_OFFER_CARD — the 15-second timeout handles the stuck case,
        // and the DETAIL screen appearing triggers scrapeAndRecord directly.
    }

    /**
     * Finds the tappable node for the offer card by enumerating RecyclerView children directly.
     *
     * This avoids the fragile "find ancestor" approach. Instead:
     *  1. Get the homeBottomSheetRecyclerView
     *  2. Walk its direct children (one per card/row)
     *  3. Find the child whose text contains BOTH "estimate" and a "$X.XX" dollar amount
     *  4. Within that child, find the best clickable (not ACCEPT/REJECT)
     *  5. If no clickable found, return the item itself for a gesture tap at its center
     */
    private fun findOfferCardTapTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val recycler = findNodeById(root, ID_RECYCLER) ?: run {
            SparkLogger.w(TAG, "findOfferCardTapTarget: RecyclerView not found")
            return null
        }

        for (i in 0 until recycler.childCount) {
            val item = recycler.getChild(i) ?: continue
            val texts = mutableListOf<String>()
            collectAllText(item, texts)

            val hasEstimate = texts.any { it.contains("estimate", ignoreCase = true) }
            val hasDollar   = texts.any { it.matches(Regex("\\$[0-9]+\\.?[0-9]*.*")) }

            SparkLogger.d(TAG, "findOfferCardTapTarget: item[$i] estimate=$hasEstimate dollar=$hasDollar texts=${texts.take(5)}")

            if (hasEstimate && hasDollar) {
                SparkLogger.i(TAG, "findOfferCardTapTarget: offer card at RecyclerView[$i]")
                recycler.recycle()

                // Try to find a clickable inside the card that is NOT ACCEPT or REJECT
                val clickable = findBestClickableInOfferCard(item)
                if (clickable != null && clickable !== item) {
                    item.recycle()
                }
                return clickable ?: item
            }
            item.recycle()
        }

        recycler.recycle()
        SparkLogger.w(TAG, "findOfferCardTapTarget: no offer card item found in RecyclerView")
        return null
    }

    /**
     * Within an offer card node, finds the best clickable target:
     * - Skips ACCEPT and REJECT buttons
     * - Prefers the ">" arrow (chevron) button if identifiable
     * - Falls back to the first non-ACCEPT/REJECT clickable
     * - Returns null if nothing usable found (caller will gesture-tap the card directly)
     */
    private fun findBestClickableInOfferCard(card: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val clickables = mutableListOf<AccessibilityNodeInfo>()
        collectAllClickables(card, clickables)

        SparkLogger.d(TAG, "findBestClickableInOfferCard: ${clickables.size} clickables found in card")

        val filtered = clickables.filter { node ->
            val text = node.text?.toString()?.lowercase() ?: ""
            val id   = node.viewIdResourceName?.lowercase() ?: ""
            val isAcceptReject = text.contains("accept") || text.contains("reject") ||
                    id.contains("accept") || id.contains("reject")
            if (isAcceptReject) SparkLogger.d(TAG, "findBest: skipping accept/reject: text='$text' id=$id")
            !isAcceptReject
        }

        SparkLogger.d(TAG, "findBestClickableInOfferCard: ${filtered.size} after filtering ACCEPT/REJECT")

        // Clean up nodes we won't use
        clickables.filter { it !in filtered }.forEach { it.recycle() }

        if (filtered.isEmpty()) {
            SparkLogger.w(TAG, "findBestClickableInOfferCard: no usable clickable — will gesture tap card center")
            return null
        }

        // Return first usable clickable; recycle the rest
        filtered.drop(1).forEach { it.recycle() }
        SparkLogger.i(TAG, "findBestClickableInOfferCard: using id=${filtered[0].viewIdResourceName}")
        return filtered[0]
    }

    private fun collectAllClickables(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllClickables(child, out)
            if (!out.contains(child)) child.recycle()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // DETAIL screen: scrape trip data → write CSV → accept or reject on detail
    //   Acceptance: tap acceptButton → CONFIRMING_ACCEPT
    //   Rejection:  tap rejectButton (retry every 3 s) → IDLE via HOME_SEARCHING
    // ──────────────────────────────────────────────────────────────────────────

    private fun scrapeAndRecord(root: AccessibilityNodeInfo) {
        scrapeRetries++
        SparkLogger.i(TAG, "scrapeAndRecord: detail screen (attempt $scrapeRetries/$MAX_SCRAPE_RETRIES)")
        if (scrapeRetries == 1) dumpScreenIds(root, "DETAIL_SCREEN")

        val details = scrapeDetailScreen(root)
        if (details == null) {
            if (scrapeRetries >= MAX_SCRAPE_RETRIES) {
                SparkLogger.e(TAG, "scrapeAndRecord: gave up after $scrapeRetries attempts — resetting IDLE")
                scrapeRetries = 0
                state = State.IDLE
                broadcastStatus("Could not read offer details — watching for next offer")
            } else {
                SparkLogger.w(TAG, "scrapeAndRecord: detail not ready — will retry on next event")
            }
            return
        }

        scrapeRetries = 0
        SparkLogger.i(TAG, "scrapeAndRecord: scraped OK — " +
                "sparkPay=\$${String.format("%.2f", details.sparkPay)} " +
                "tip=\$${String.format("%.2f", details.tipPay)} " +
                "payHourly=\$${String.format("%.2f", details.payHourly)}/hr " +
                "dist=${details.distanceMiles?.let { "${it}mi" } ?: "n/a"} " +
                "time=${details.timeMinutes}min " +
                "delivery=\$${details.deliveryPay?.let { String.format("%.2f", it) } ?: "n/a"} " +
                "extra=\$${details.extraEarnings?.let { String.format("%.2f", it) } ?: "n/a"} " +
                "tipStatus=${details.tipStatus ?: "confirmed"} " +
                "qty=${details.totalItemQty ?: "n/a"} " +
                "pickup='${details.pickupType ?: "n/a"}' " +
                "store='${details.pickupStore ?: "n/a"}' " +
                "tags=${details.tags} " +
                "offerType=${details.offerType}")

        // Write to CSV — skip if this is a duplicate of the last offer within DEDUP_WINDOW_MS.
        // This prevents logging the same offer twice when Spark re-presents it after an expiry dialog.
        val offerKey    = OfferKey(details.sparkPay, details.tipPay, details.timeMinutes)
        val now2        = System.currentTimeMillis()
        val isDuplicate = offerKey == lastRecordedKey && now2 - lastRecordedTime < DEDUP_WINDOW_MS
        if (isDuplicate) {
            val ageSec = (now2 - lastRecordedTime) / 1_000
            SparkLogger.w(TAG, "scrapeAndRecord: duplicate offer (${ageSec}s since last record) — skipping CSV write")
        }

        // ── Criteria evaluation ────────────────────────────────────────────────
        // ── Per-criterion evaluation with individual failure reasons ──────────────
        val rejectReasons = mutableListOf<String>()
        val s = AppSettings  // alias for brevity
        if (details.tipPay    < s.minTipAmount)
            rejectReasons += "tipPay=${String.format("%.2f", details.tipPay)} < min=${String.format("%.2f", s.minTipAmount)}"
        if (details.tipHourly < s.minTipHourly)
            rejectReasons += "tipHourly=${String.format("%.2f", details.tipHourly)}/hr < min=${String.format("%.2f", s.minTipHourly)}/hr"
        if (details.totalPay  < s.minTotalPay)
            rejectReasons += "totalPay=${String.format("%.2f", details.totalPay)} < min=${String.format("%.2f", s.minTotalPay)}"
        if (details.payHourly < s.minPayHourly)
            rejectReasons += "payHourly=${String.format("%.2f", details.payHourly)}/hr < min=${String.format("%.2f", s.minPayHourly)}/hr"
        details.distanceMiles?.let { d ->
            if (d > s.maxDistance)
                rejectReasons += "dist=${d}mi > max=${String.format("%.1f", s.maxDistance)}mi"
        }
        if (details.dollarsPerMile < s.minDollarsPerMile)
            rejectReasons += "$/mi=${String.format("%.2f", details.dollarsPerMile)} < min=${String.format("%.2f", s.minDollarsPerMile)}"
        val meetsAll       = rejectReasons.isEmpty()
          val criteriaResult = if (meetsAll) "PASS" else "FAIL"
          if (!isDuplicate) {
              CsvLogger.append(details, criteriaResult)
              lastRecordedKey      = offerKey
              lastRecordedTime     = now2
          }
          lastOfferCsvWritten = !isDuplicate
        if (meetsAll) {
            SparkLogger.i(TAG, "scrapeAndRecord: criteria check → ACCEPT " +
                    "(tipPay=${String.format("%.2f", details.tipPay)}, " +
                    "tipHourly=${String.format("%.2f", details.tipHourly)}/hr, " +
                    "totalPay=${String.format("%.2f", details.totalPay)}, " +
                    "payHourly=${String.format("%.2f", details.payHourly)}/hr, " +
                    "dist=${details.distanceMiles}mi, $/mi=${String.format("%.2f", details.dollarsPerMile)})")
        } else {
            SparkLogger.i(TAG, "scrapeAndRecord: criteria check → REJECT | " +
                    rejectReasons.joinToString(" | ") + " | settings: " +
                    "minTip=${String.format("%.2f", s.minTipAmount)} " +
                    "minTipHr=${String.format("%.2f", s.minTipHourly)}/hr " +
                    "minTotal=${String.format("%.2f", s.minTotalPay)} " +
                    "minPayHr=${String.format("%.2f", s.minPayHourly)}/hr " +
                    "maxDist=${String.format("%.1f", s.maxDistance)}mi" +
                    "min$/mi=${String.format("%.2f", s.minDollarsPerMile)}")
        }

        if (meetsAll) {
            // ── ACCEPT ─────────────────────────────────────────────────────────
            // State = ACCEPTING until the tap fires, then CONFIRMING_ACCEPT.
            // If the offer expires before the tap fires, the ACCEPTING handler
            // catches the Got It modal or HOME_SEARCHING and resets to IDLE.
            val acceptDelay = AppSettings.randomClickAcceptDelay()
            broadcastStatus("✓ Offer meets criteria! Accepting in ${acceptDelay}ms…")
            state = State.ACCEPTING
            scheduleAcceptingTimeout()
            // tapHandler runs on AaaDisTapThread — bypasses Samsung main-Handler throttling.
            // All state mutations are posted back to mainHandler so they stay on the main thread.
            tapHandler.postDelayed({
                if (state == State.ACCEPTING) {
                    SparkLogger.i(TAG, "ACCEPTING: tapping accept button on detail screen")
                    val tapped = tryAcceptOnDetailScreen()
                    mainHandler.post {
                        if (state == State.ACCEPTING) {
                            if (tapped) {
                                SparkLogger.i(TAG, "ACCEPTING: accept tapped — watching for Start Trip confirmation")
                                broadcastStatus("Accept tapped — confirming…")
                                cancelAcceptingTimeout()
                                state = State.CONFIRMING_ACCEPT
                                scheduleConfirmAcceptTimeout()
                            } else {
                                // Accept button not found — detail screen may still be loading or button
                                // ID changed.  Log what IS on screen and stay ACCEPTING so the retry
                                // loop (via accessibility events on DETAIL) will try again in
                                // REJECT_DETAIL_RETRY_MS.  Do NOT fall back to reject — the criteria
                                // said accept, so we keep trying until timeout.
                                SparkLogger.w(TAG, "ACCEPTING: accept button not found on detail screen — staying ACCEPTING, will retry via events")
                                broadcastStatus("Accept button not found — retrying…")
                                val debugRoot = rootInActiveWindow
                                if (debugRoot != null) {
                                    dumpScreenIds(debugRoot, "ACCEPTING/NO_BUTTON_FOUND")
                                    debugRoot.recycle()
                                }
                            }
                        }
                    }
                }
            }, acceptDelay)
        } else {
            // ── REJECT ─────────────────────────────────────────────────────────
            // State = REJECTING.  The reject button is tapped after a random human-like
            // delay; if not found, the REJECTING + DETAIL handler retries every 3 s.
            // No navigation away from the detail screen ever occurs.
            val rejectDelay = AppSettings.randomClickRejectDelay()
            broadcastStatus("✗ Offer below criteria — rejecting in ${rejectDelay}ms…")
            state = State.REJECTING
            // Use Long.MAX_VALUE sentinel to block the event-driven retry loop until
            // the initial random delay fires.
            lastDetailRejectAttemptTime = Long.MAX_VALUE
            scheduleAutoRejectTimeout()
            // tapHandler runs on AaaDisTapThread — bypasses Samsung main-Handler throttling.
            // lastDetailRejectAttemptTime is @Volatile; no other state mutations occur here.
            tapHandler.postDelayed({
                if (state == State.REJECTING) {
                    SparkLogger.i(TAG, "REJECTING: initial delayed tap — attempting reject button on detail screen")
                    // Enable event-driven retries from this moment forward
                    lastDetailRejectAttemptTime = System.currentTimeMillis()
                    val tapped = tryDeclineOnDetailScreen()
                    if (!tapped) {
                        SparkLogger.w(TAG, "REJECTING: reject button not found on initial attempt — event-driven retries will continue")
                        val debugRoot = rootInActiveWindow
                        if (debugRoot != null) {
                            dumpScreenIds(debugRoot, "REJECTING/INITIAL_ATTEMPT/NO_BUTTON_FOUND")
                            debugRoot.recycle()
                        }
                    }
                }
            }, rejectDelay)
        }
    }

    /**
     * Looks for the Accept button on the detail screen and taps it.
     * Uses rootInActiveWindow for a fresh, non-stale tree.
     * Returns true if the button was found and tapped.
     */
    private fun tryAcceptOnDetailScreen(): Boolean {
        val root = rootInActiveWindow ?: run {
            SparkLogger.w(TAG, "tryAcceptOnDetailScreen: rootInActiveWindow is null")
            return false
        }
        try {
            val byId = findNodeById(root, ID_ACCEPT_BUTTON_DETAIL)
            if (byId != null) {
                tapNode(byId)
                SparkLogger.i(TAG, "tryAcceptOnDetailScreen: gesture tap on acceptButton")
                byId.recycle()
                return true
            }
            val btn = findClickableByText(root, listOf("accept", "accept offer"))
            if (btn == null) {
                SparkLogger.w(TAG, "tryAcceptOnDetailScreen: no accept button found on detail screen")
                return false
            }
            SparkLogger.i(TAG, "tryAcceptOnDetailScreen: found by text — gesture tap")
            tapNode(btn)
            btn.recycle()
            return true
        } finally {
            root.recycle()
        }
    }

    /**
     * No-arg overload of tryDeclineOnDetailScreen — fetches a fresh rootInActiveWindow.
     * Used in postDelayed lambdas where the original root may be stale.
     */
    private fun tryDeclineOnDetailScreen(): Boolean {
        val root = rootInActiveWindow ?: run {
            SparkLogger.w(TAG, "tryDeclineOnDetailScreen: rootInActiveWindow is null")
            return false
        }
        return try {
            tryDeclineOnDetailScreen(root)
        } finally {
            root.recycle()
        }
    }

    /**
     * Called on every event while in CONFIRMING_ACCEPT.
     *
     * Watches for two outcomes after tapping Accept:
     *  1. Start Trip screen appears → trip confirmed → pause monitoring
     *  2. "Offer unavailable" / "no longer available" dialog → accept failed → resume monitoring
     *
     * A 20-second timeout (scheduleConfirmAcceptTimeout) fires if neither screen appears.
     */
    private fun handleConfirmingAccept(root: AccessibilityNodeInfo, screen: AppScreen) {
        // ── 1. Check for "Offer unavailable" (same gotItButton used in reject flow) ──
        val gotIt = findNodeById(root, ID_GOT_IT_BUTTON)
            ?: findClickableByText(root, listOf("got it"))
        if (gotIt != null) {
            if (lastOfferCsvWritten) CsvLogger.updateLastActionResult("ACCEPT_UNAVAILABLE")
              SparkLogger.i(TAG, "CONFIRMING_ACCEPT: offer-unavailable dialog — accept failed, resuming monitoring")
            tapGotItWithRetry(gotIt)
            gotIt.recycle()
            cancelConfirmAcceptTimeout()
            isMonitoring = true
            state = State.IDLE
            broadcastStatus("Offer no longer available — monitoring resumed")
            return
        }

        // ── 2. Check for "no longer available" text as a broader fallback ──
        val allTexts = mutableListOf<String>()
        collectAllText(root, allTexts)
        val offerGone = allTexts.any { text ->
            text.contains("no longer available", ignoreCase = true) ||
            text.contains("offer unavailable", ignoreCase = true) ||
            text.contains("offer expired", ignoreCase = true)
        }
        if (offerGone) {
            if (lastOfferCsvWritten) CsvLogger.updateLastActionResult("ACCEPT_UNAVAILABLE")
              SparkLogger.i(TAG, "CONFIRMING_ACCEPT: 'no longer available' text detected — accept failed, resuming monitoring")
            cancelConfirmAcceptTimeout()
            isMonitoring = true
            state = State.IDLE
            broadcastStatus("Offer no longer available — monitoring resumed")
            return
        }

        // ── 3. Check for Start Trip screen ──
        val isStartTrip = allTexts.any { text ->
            START_TRIP_TEXTS.any { keyword -> text.equals(keyword, ignoreCase = true) }
        }
        if (isStartTrip) {
            if (lastOfferCsvWritten) CsvLogger.updateLastActionResult("ACCEPTED")
              playHorn()
              SparkLogger.i(TAG, "CONFIRMING_ACCEPT: Start Trip screen detected — trip confirmed! Pausing monitoring.")
            cancelConfirmAcceptTimeout()
            isMonitoring = false
            state = State.IDLE
            broadcastStatus("Trip accepted and confirmed! Monitoring paused — resume in the app.")
            return
        }

        // Still transitioning — wait for the next event
        SparkLogger.d(TAG, "CONFIRMING_ACCEPT: still waiting for confirmation (screen=$screen, texts=${allTexts.take(5)})")
    }

    /**
     * Posts a 20-second fallback timeout for CONFIRMING_ACCEPT.
     * If neither the Start Trip screen nor "offer unavailable" appears within that window,
     * we resume monitoring so the app doesn't get stuck.
     */
    private fun scheduleConfirmAcceptTimeout() {
        val runnable = Runnable {
            if (state == State.CONFIRMING_ACCEPT) {
                if (lastOfferCsvWritten) CsvLogger.updateLastActionResult("ACCEPT_TIMEOUT")
                  SparkLogger.w(TAG, "CONFIRMING_ACCEPT: timeout — neither Start Trip nor unavailable detected, resuming monitoring")
                broadcastStatus("Accept confirmation timed out — monitoring resumed")
                isMonitoring = true
                state = State.IDLE
            }
        }
        confirmAcceptTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, CONFIRM_ACCEPT_TIMEOUT)
    }

    private fun cancelConfirmAcceptTimeout() {
        confirmAcceptTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        confirmAcceptTimeoutRunnable = null
    }

    /**
     * Schedules a 30-second safety timeout for the ACCEPTING state.
     * If the accept tap fires but neither Start Trip nor Got It appears within this window
     * (e.g. screen froze), we reset to IDLE so monitoring can resume.
     */
    private fun scheduleAcceptingTimeout() {
        cancelAcceptingTimeout()
        val r = Runnable {
            if (state == State.ACCEPTING) {
                if (lastOfferCsvWritten) CsvLogger.updateLastActionResult("ACCEPT_TIMEOUT")
                  SparkLogger.w(TAG, "ACCEPTING: 30s timeout — accept flow stuck, resetting IDLE")
                broadcastStatus("Accept timed out — watching for next offer")
                state = State.IDLE
            }
        }
        acceptingTimeoutRunnable = r
        mainHandler.postDelayed(r, 30_000L)
    }

    private fun cancelAcceptingTimeout() {
        acceptingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        acceptingTimeoutRunnable = null
    }

    /**
     * Schedules a 15-second safety timeout for TAPPING_OFFER_CARD.
     * If the detail screen never appears (e.g. the tap missed or Android deferred it),
     * we reset to IDLE so the next offer can be handled cleanly.
     */
    private fun scheduleTappingCardTimeout() {
        cancelTappingCardTimeout()
        val r = Runnable {
            if (state == State.TAPPING_OFFER_CARD) {
                SparkLogger.w(TAG, "TAPPING_OFFER_CARD: timeout — detail screen never appeared after ${TAPPING_CARD_TIMEOUT_MS / 1000}s, resetting IDLE")
                broadcastStatus("Tap timed out — watching for next offer")
                state = State.IDLE
                lastWaitingLogTime = 0L
            }
        }
        tappingCardTimeoutRunnable = r
        mainHandler.postDelayed(r, TAPPING_CARD_TIMEOUT_MS)
    }

    private fun cancelTappingCardTimeout() {
        tappingCardTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        tappingCardTimeoutRunnable = null
    }

    /**
     * Schedules a 30-second safety timeout for the REJECTING state.
     * If the reject button is never found and the offer never expires (unexpected),
     * we reset to IDLE so monitoring can resume.
     */
    private fun scheduleAutoRejectTimeout() {
        cancelAutoRejectTimeout()
        val r = Runnable {
            if (state == State.REJECTING) {
                if (lastOfferCsvWritten) CsvLogger.updateLastActionResult("REJECT_TIMEOUT")
                  SparkLogger.w(TAG, "REJECTING: timeout — reject button never found after ${AUTO_REJECT_TIMEOUT_MS / 1000}s, resetting IDLE")
                broadcastStatus("Reject timed out — watching for next offer")
                state = State.IDLE
                lastWaitingLogTime = 0L
            }
        }
        autoRejectTimeoutRunnable = r
        mainHandler.postDelayed(r, AUTO_REJECT_TIMEOUT_MS)
    }

    private fun cancelAutoRejectTimeout() {
        autoRejectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        autoRejectTimeoutRunnable = null
    }

    // ── Acceptance horn ───────────────────────────────────────────────────────
    /**
     * Plays the device alarm ringtone at maximum volume for 3 seconds when an
     * offer is successfully accepted so the driver hears it immediately.
     * Volume is restored to its original level afterwards.
     */
    private fun playHorn() {
        try {
            val am       = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stream   = AudioManager.STREAM_ALARM
            val savedVol = am.getStreamVolume(stream)
            am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0)

            val uri      = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ringtone?.play()
            SparkLogger.i(TAG, "playHorn: alarm started")

            mainHandler.postDelayed({
                ringtone?.stop()
                am.setStreamVolume(stream, savedVol, 0)
                SparkLogger.i(TAG, "playHorn: alarm stopped, volume restored")
            }, 3_000L)
        } catch (e: Exception) {
            SparkLogger.e(TAG, "playHorn: failed", e)
        }
    }

    /**
     * Looks for the Reject button on the detail screen and taps it if found.
     *
     * Strategy (in order):
     *  1. findAccessibilityNodeInfosByViewId for the confirmed ID (rejectButton).
     *     This queries the live accessibility service, NOT the cached subtree — so it
     *     works even when the root object was captured before the buttons fully rendered.
     *  2. Text-based walk of the node tree as fallback.
     *
     * Returns true if a button was found and tapped, false otherwise.
     */
    private fun tryDeclineOnDetailScreen(root: AccessibilityNodeInfo): Boolean {
        // ID lookup via service (reliable even if root was captured early)
        val byId = findNodeById(root, ID_REJECT_BUTTON_DETAIL)
        if (byId != null) {
            tapNode(byId)
            SparkLogger.i(TAG, "tryDeclineOnDetailScreen: gesture tap on rejectButton")
            byId.recycle()
            broadcastStatus("Declining offer on detail screen…")
            return true
        }
        // Text-based fallback (catches any future ID rename by Spark)
        val btn = findClickableByText(root, listOf("decline", "reject", "no thanks", "no, thanks"))
        if (btn == null) {
            SparkLogger.w(TAG, "tryDeclineOnDetailScreen: no decline button found on detail screen")
            return false
        }
        SparkLogger.i(TAG, "tryDeclineOnDetailScreen: found by text — gesture tap")
        tapNode(btn)
        btn.recycle()
        broadcastStatus("Declining offer on detail screen…")
        return true
    }

    /**
     * Walks the full tree looking for a clickable node whose text or content-description
     * contains any of [keywords] (case-insensitive).
     */
    private fun findClickableByText(
        root: AccessibilityNodeInfo,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectAllClickables(root, all)
        for (node in all) {
            val text = (node.text?.toString() ?: "") + (node.contentDescription?.toString() ?: "")
            if (keywords.any { text.contains(it, ignoreCase = true) }) {
                all.filter { it !== node }.forEach { it.recycle() }
                return node
            }
        }
        all.forEach { it.recycle() }
        return null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ID / tree dump — called at every stage transition to capture screen state
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Walks the full accessibility tree and logs every node that has either a
     * resource ID or visible text.  Log tag: "IdDump".
     *
     * Format per line:
     *   [CLICK] id=<id>  text='<text>'  cd='<contentDesc>'  class=<simpleClassName>
     *
     * Logs under "IdDump" so you can filter with: tag:IdDump
     */
    private fun dumpScreenIds(root: AccessibilityNodeInfo, label: String) {
        SparkLogger.i("IdDump", "══════ $label ══════")
        dumpNode(root, depth = 0)
        SparkLogger.i("IdDump", "══════ end $label ══════")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        val id    = node.viewIdResourceName?.substringAfterLast("/") ?: ""
        val text  = node.text?.toString()?.take(60) ?: ""
        val cd    = node.contentDescription?.toString()?.take(60) ?: ""
        val cls   = node.className?.toString()?.substringAfterLast(".") ?: ""
        val click = if (node.isClickable) "[CLICK]" else "       "

        // Only log nodes that carry at least one useful piece of information
        if (id.isNotEmpty() || text.isNotEmpty() || cd.isNotEmpty()) {
            val indent = "  ".repeat(depth.coerceAtMost(8))
            SparkLogger.i("IdDump", "$indent$click id=$id  text='$text'  cd='$cd'  class=$cls")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1)
            child.recycle()
        }
    }

    /**
     * Scrapes the offer detail screen using resource IDs from the actual XML hierarchy.
     *
     * Confirmed fields (com.walmart.sparkdriver:id/...):
     *  Header bar:     stopCount, distance, time
     *  Pay breakdown:  estimatedValueView, labelView/valueView pairs (Delivery, Extra Earnings, Tips),
     *                  tagText (tip status), tipsView (tip container)
     *  Pickup stop:    subTitle (item qty), title (stop type), address_name (store)
     *  All stops:      chip (offer tags — Pharmacy, Shopping, Heavy item, etc.)
     */
    private fun scrapeDetailScreen(root: AccessibilityNodeInfo): OfferDetails? {
        // ── Debug: log which key IDs are present on this detail screen ───────
        val keyIds = listOf(ID_ESTIMATED_LABEL, ID_ESTIMATED_VALUE, ID_TIPS_VIEW,
            ID_TIME, ID_DISTANCE, ID_STOP_COUNT, ID_VALUE_VIEW)
        val presentIds = keyIds.filter { hasNodeWithId(root, it) }
            .map { it.substringAfterLast("/") }
        SparkLogger.d(TAG, "detail screen IDs present: $presentIds")

        // ── Time (duration) ─────────────────────────────────────────────────
        // Must be present — without time we can't compute an hourly rate at all
        val timeMinutes = findTimeMinutes(root)
        SparkLogger.d(TAG, "scrape time: $timeMinutes min")
        if (timeMinutes == null) {
            SparkLogger.w(TAG, "scrape FAIL: timeMinutes not found")
            return null
        }

        // ── Estimated total ─────────────────────────────────────────────────
        val payText = findTextByNodeId(root, ID_ESTIMATED_VALUE)
        val estimatedPay = payText?.let { extractDollar(it) }
        SparkLogger.d(TAG, "scrape estimatedPay: text='$payText' → $estimatedPay")

        // ── Pay breakdown (Delivery, Extra Earnings) ─────────────────────────
        // labelView/valueView nodes only appear in the pay breakdown section —
        // stop rows use title/address_name/etc. so zipping them is safe.
        val payBreakdown = scrapePayBreakdown(root)
        val deliveryPay   = payBreakdown["Delivery"]
        val extraEarnings = payBreakdown["Extra Earnings"]
        SparkLogger.d(TAG, "scrape breakdown: delivery=\$$deliveryPay extra=\$$extraEarnings")

        // ── Tip amount ──────────────────────────────────────────────────────
        // tipsView is only present in the accessibility tree when Spark shows a customer tip.
        // When absent (0 nodes), the offer has no tip — default to $0.
        // Do NOT fall back to other valueView nodes — they contain unrelated amounts.
        val tipContainerText = findValueInContainer(root, ID_TIPS_VIEW, ID_VALUE_VIEW)
        val tip: Double = if (tipContainerText != null) {
            val amount = extractDollar(tipContainerText) ?: 0.0
            SparkLogger.d(TAG, "scrape tip: tipsView → '$tipContainerText' → $amount")
            amount
        } else {
            SparkLogger.d(TAG, "scrape tip: tipsView absent — no customer tip, defaulting to \$0")
            0.0
        }

        // ── Tip status (PENDING, confirmed, etc.) ───────────────────────────
        val tipStatus = scrapeTipStatus(root)
        SparkLogger.d(TAG, "scrape tipStatus: $tipStatus")

        // ── Distance ────────────────────────────────────────────────────────
        val distText = findTextByNodeId(root, ID_DISTANCE)
        val distMiles = distText?.let { extractMiles(it) }
        SparkLogger.d(TAG, "scrape distance: text='$distText' → $distMiles")

        // ── Stop count ──────────────────────────────────────────────────────
        val stopsText = findTextByNodeId(root, ID_STOP_COUNT)
        SparkLogger.d(TAG, "scrape stops: '$stopsText'")

        // ── Total item qty from pickup subTitle ──────────────────────────────
        val totalItemQty = scrapeTotalItemQty(root)
        SparkLogger.d(TAG, "scrape totalItemQty: $totalItemQty")

        // ── Collect all title nodes once — shared by pickupType and offerType ──
        // "Just for you" is confirmed (2026-03-24 log) to appear as a title node
        // on the detail screen, above the pickup stop. Collecting once prevents
        // the timing issue where recycled nodes cause collectAllText to miss it.
        val allTitles = scrapeTitles(root)
        SparkLogger.d(TAG, "scrape allTitles: $allTitles")

        // ── Pickup stop type and store name ──────────────────────────────────
        val pickupType  = scrapePickupType(allTitles)
        val pickupStore = scrapePickupStore(root)
        SparkLogger.d(TAG, "scrape pickup: type='$pickupType' store='$pickupStore'")

        // ── Offer tags (chips) ───────────────────────────────────────────────
        val tags = scrapeChips(root)
        SparkLogger.d(TAG, "scrape tags: $tags")

        // ── Offer type (JFY vs FCFS) ─────────────────────────────────────────
        // "Just for you" appears as a title node on the detail screen (confirmed).
        // Check titles first (most reliable), then chips, then full screen text.
        val offerType = determineOfferType(allTitles, tags)
        SparkLogger.d(TAG, "scrape offerType: $offerType")

        return OfferDetails(
            tipDollars          = tip,
            timeMinutes         = timeMinutes,
            estimatedPayDollars = estimatedPay,
            distanceMiles       = distMiles,
            stopCount           = stopsText?.let { extractNumber(it)?.toInt() },
            deliveryPay         = deliveryPay,
            extraEarnings       = extraEarnings,
            tipStatus           = tipStatus,
            totalItemQty        = totalItemQty,
            pickupType          = pickupType,
            pickupStore         = pickupStore,
            tags                = tags,
            offerType           = offerType
        )
    }

    /**
     * Scrapes the pay breakdown section (Delivery, Extra Earnings, Tips pay amounts).
     *
     * labelView/valueView nodes are exclusively in the pay breakdown section of the
     * detail screen — stop rows use title/address_name/etc. So zipping both lists
     * by index gives a reliable label → value mapping.
     *
     * Example: ["Delivery" → 14.71, "Extra Earnings" → 0.70, "Tips" → 5.00]
     */
    private fun scrapePayBreakdown(root: AccessibilityNodeInfo): Map<String, Double> {
        val labelNodes = try { root.findAccessibilityNodeInfosByViewId(ID_LABEL_VIEW) } catch (_: Exception) { null }
        val valueNodes = try { root.findAccessibilityNodeInfosByViewId(ID_VALUE_VIEW) } catch (_: Exception) { null }

        val labels = labelNodes?.map { it.text?.toString() ?: "" } ?: emptyList()
        labelNodes?.forEach { it.recycle() }
        val values = valueNodes?.map { it.text?.toString() ?: "" } ?: emptyList()
        valueNodes?.forEach { it.recycle() }

        val result = mutableMapOf<String, Double>()
        val count = minOf(labels.size, values.size)
        for (i in 0 until count) {
            val label = labels[i].trim()
            val value = extractDollar(values[i])
            if (label.isNotEmpty() && value != null) result[label] = value
        }
        return result
    }

    /**
     * Reads the tagText node that Spark places next to the tip amount.
     * Value is "PENDING" when the customer has not yet confirmed a tip,
     * absent entirely when the tip is already confirmed or there is no tip.
     */
    private fun scrapeTipStatus(root: AccessibilityNodeInfo): String? {
        val nodes = try { root.findAccessibilityNodeInfosByViewId(ID_TAG_TEXT) } catch (_: Exception) { null }
            ?: return null
        val status = nodes.firstOrNull()?.text?.toString()?.takeIf { it.isNotBlank() }
        nodes.forEach { it.recycle() }
        return status
    }

    /**
     * Parses the item quantity from the pickup subTitle: "0 items (2 qty)" → 2.
     * subTitle appears once on the detail screen, at the pickup stop.
     */
    private fun scrapeTotalItemQty(root: AccessibilityNodeInfo): Int? {
        val nodes = try { root.findAccessibilityNodeInfosByViewId(ID_SUB_TITLE) } catch (_: Exception) { null }
            ?: return null
        val text = nodes.firstOrNull()?.text?.toString()
        nodes.forEach { it.recycle() }
        return text?.let {
            Regex("\\((\\d+)\\s*qty\\)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /**
     * Collects all title node texts from the detail screen in tree order.
     * Shared between scrapePickupType() and determineOfferType() so both
     * operate on the same live snapshot without re-querying.
     */
    private fun scrapeTitles(root: AccessibilityNodeInfo): List<String> {
        val nodes = try { root.findAccessibilityNodeInfosByViewId(ID_TITLE) } catch (_: Exception) { null }
            ?: return emptyList()
        val result = nodes.mapNotNull { it.text?.toString()?.takeIf { t -> t.isNotBlank() } }
        nodes.forEach { it.recycle() }
        return result
    }

    /**
     * Returns the stop type of the first (pickup) stop from a pre-collected title list.
     *
     * Title nodes on the detail screen appear in order:
     *   "X stops"         ← skip (stop-count header)
     *   "Just for you"    ← skip (JFY badge — handled separately by determineOfferType)
     *   "Curbside pickup" ← first real pickup type  ← return this
     *   "Drop-off"        ← skip
     *   "Drop-off"        ← skip …
     */
    private fun scrapePickupType(titles: List<String>): String? {
        val stopCountPattern = Regex("^\\d+\\s+stops?$", RegexOption.IGNORE_CASE)
        return titles.firstOrNull { text ->
            !stopCountPattern.containsMatchIn(text) &&
            !text.equals("Drop-off", ignoreCase = true) &&
            !text.equals("Delivery", ignoreCase = true) &&
            !JFY_KEYWORDS.any { kw -> text.contains(kw, ignoreCase = true) }
        }
    }

    /**
     * Returns the name of the pickup store (first address_name in the detail screen).
     * The pickup stop always appears first in the stop list, so the first address_name
     * is always the store (e.g. "Sam's Club Pharmacy 4735").
     * Drop-off address_name values are customer names (e.g. "GREG V.").
     */
    private fun scrapePickupStore(root: AccessibilityNodeInfo): String? {
        val nodes = try { root.findAccessibilityNodeInfosByViewId(ID_ADDRESS_NAME) } catch (_: Exception) { null }
            ?: return null
        val store = nodes.firstOrNull()?.text?.toString()?.takeIf { it.isNotBlank() }
        nodes.forEach { it.recycle() }
        return store
    }

    /**
     * Returns all chip texts from the detail screen (Pharmacy, Shopping, Heavy item, etc.).
     * Chips appear in both tier1ChipContainer (offer type) and tier2ChipContainer (delivery tags).
     */
    private fun scrapeChips(root: AccessibilityNodeInfo): List<String> {
        val nodes = try { root.findAccessibilityNodeInfosByViewId(ID_CHIP) } catch (_: Exception) { null }
            ?: return emptyList()
        val chips = nodes.mapNotNull { it.text?.toString()?.takeIf { t -> t.isNotBlank() } }
        nodes.forEach { it.recycle() }
        return chips
    }

    /**
     * Determines if an offer is "Just for You" (JFY) or first-come-first-served (FCFS).
     *
     * Confirmed (2026-03-24): Spark renders "Just for you" as a title node on the
     * detail screen, appearing before the pickup stop's title. We check the pre-collected
     * title list first (most reliable), then chip texts as a secondary check.
     */
    private fun determineOfferType(titles: List<String>, chips: List<String>): String {
        if (titles.any { text -> JFY_KEYWORDS.any { text.contains(it, ignoreCase = true) } }) return "JFY"
        if (chips.any  { text -> JFY_KEYWORDS.any { text.contains(it, ignoreCase = true) } }) return "JFY"
        return "FCFS"
    }

    /**
     * Scans all valueView nodes on screen for a dollar amount different from [excludeAmount].
     * Used as a fallback when tipsView is absent — some offer types omit the tip container
     * but may still show the tip amount in a generic valueView.
     */
    private fun findTipAmongValueViews(root: AccessibilityNodeInfo, excludeAmount: Double?): Double? {
        val nodes = try {
            root.findAccessibilityNodeInfosByViewId(ID_VALUE_VIEW)
        } catch (e: Exception) { null } ?: return null

        var result: Double? = null
        for (node in nodes) {
            val text = node.text?.toString() ?: ""
            val amount = extractDollar(text)
            SparkLogger.d(TAG, "findTipAmongValueViews: text='$text' amount=$amount exclude=$excludeAmount")
            if (amount != null && amount != excludeAmount) {
                result = amount
                node.recycle()
                break
            }
            node.recycle()
        }
        nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
        return result
    }

    /**
     * Finds the time field that contains "hr" or "min" (not "ASAP").
     * There are multiple nodes with ID_TIME — we want the one in the trip summary
     * that looks like "• 1 hr, 1 min".
     */
    private fun findTimeMinutes(root: AccessibilityNodeInfo): Double? {
        val nodes = try {
            root.findAccessibilityNodeInfosByViewId(ID_TIME)
        } catch (e: Exception) {
            SparkLogger.e(TAG, "findAccessibilityNodeInfosByViewId($ID_TIME) threw", e)
            null
        } ?: return null

        var result: Double? = null
        for (node in nodes) {
            val text = node.text?.toString() ?: ""
            SparkLogger.d(TAG, "findTimeMinutes: candidate text='$text'")
            if (text.contains("hr", ignoreCase = true) || text.contains("min", ignoreCase = true)) {
                result = parseTimeToMinutes(text)
                if (result != null) {
                    node.recycle()
                    break
                }
            }
            node.recycle()
        }
        return result
    }

    /**
     * Finds a valueView child inside a specific container ID.
     * e.g. finds the "$3.80" text inside the tipsView container.
     *
     * NOTE: findAccessibilityNodeInfosByViewId() is unreliable when called on a sub-node
     * (non-root). It works fine on the root, but returns empty lists on subtree nodes even
     * when matching children exist. So we: (1) find the container from root, then (2) walk
     * its subtree manually to find the value child by resource ID.
     */
    private fun findValueInContainer(
        root: AccessibilityNodeInfo,
        containerId: String,
        valueId: String
    ): String? {
        val containers = try {
            root.findAccessibilityNodeInfosByViewId(containerId)
        } catch (e: Exception) {
            SparkLogger.e(TAG, "findValueInContainer containerId=$containerId threw", e)
            null
        } ?: return null

        SparkLogger.d(TAG, "findValueInContainer: found ${containers.size} node(s) for $containerId")

        var result: String? = null
        for (container in containers) {
            // Walk subtree manually — do NOT use findAccessibilityNodeInfosByViewId on sub-node
            result = findTextInSubtreeById(container, valueId)
            SparkLogger.d(TAG, "findValueInContainer: subtree walk for $valueId → '$result'")
            container.recycle()
            if (result != null) break
        }
        containers.forEach { try { it.recycle() } catch (_: Exception) {} }
        return result
    }

    /**
     * Recursively walks [node]'s subtree and returns the text of the first child
     * whose viewIdResourceName matches [resourceId].
     */
    private fun findTextInSubtreeById(node: AccessibilityNodeInfo, resourceId: String): String? {
        if (node.viewIdResourceName == resourceId) {
            return node.text?.toString()
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findTextInSubtreeById(child, resourceId)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Node-tree utilities
    // ──────────────────────────────────────────────────────────────────────────

    private fun hasNodeWithId(root: AccessibilityNodeInfo, resourceId: String): Boolean {
        return try {
            val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
            val found = !nodes.isNullOrEmpty()
            nodes?.forEach { it.recycle() }
            found
        } catch (e: Exception) {
            false
        }
    }

    private fun findNodeById(root: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? {
        return try {
            root.findAccessibilityNodeInfosByViewId(resourceId)?.firstOrNull()
        } catch (e: Exception) {
            SparkLogger.e(TAG, "findNodeById($resourceId) threw", e)
            null
        }
    }

    private fun findTextByNodeId(root: AccessibilityNodeInfo, resourceId: String): String? {
        return try {
            root.findAccessibilityNodeInfosByViewId(resourceId)
                ?.firstOrNull()
                ?.also { it.recycle() }
                ?.text?.toString()
        } catch (e: Exception) {
            SparkLogger.e(TAG, "findTextByNodeId($resourceId) threw", e)
            null
        }
    }

    private fun findFirstClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstClickable(child)
            if (result != null) {
                if (result !== child) child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    private fun collectAllText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectAllText(child, out)
                child.recycle()
            }
        }
    }

    /**
     * Taps the "GOT IT" button and schedules a 1500ms retry for Samsung devices
     * where dispatchGesture() is queued and may take 20+ seconds to execute.
     * The retry checks if the button is still visible and re-taps if so.
     */
    private fun tapGotItWithRetry(gotIt: AccessibilityNodeInfo) {
        lastGotItTapMs = System.currentTimeMillis()
        tapNode(gotIt)
        tapHandler.postDelayed({
            if (System.currentTimeMillis() - lastGotItTapMs < 1200L) return@postDelayed
            val freshRoot = rootInActiveWindow ?: return@postDelayed
            val freshGotIt = findNodeById(freshRoot, ID_GOT_IT_BUTTON)
                ?: findClickableByText(freshRoot, listOf("got it"))
            if (freshGotIt != null) {
                SparkLogger.w(TAG, "tapGotItWithRetry: button still present after 1500ms — retapping (Samsung gesture queue)")
                lastGotItTapMs = System.currentTimeMillis()
                tapNode(freshGotIt)
                freshGotIt.recycle()
            }
            freshRoot.recycle()
        }, 1500L)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Gesture tap — pure dispatchGesture(), no ACTION_CLICK anywhere
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Taps [node] using a randomised gesture within the inner 60% of the
     * button bounds. Duration is varied 80–150 ms for human-like cadence.
     * Returns true if dispatched, false if bounds were empty.
     * No ACTION_CLICK fallback — gesture-only for maximum stealth.
     */
    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        SparkLogger.i(TAG, "tapNode: id=${node.viewIdResourceName} bounds=${rect}")

        if (rect.isEmpty || rect.width() <= 2 || rect.height() <= 2) {
            SparkLogger.w(TAG, "tapNode: empty/zero-size bounds — skipping")
            return false
        }

        val marginX  = (rect.width()  * 0.2f).toInt().coerceAtLeast(1)
        val marginY  = (rect.height() * 0.2f).toInt().coerceAtLeast(1)
        val rangeX   = (rect.width()  - 2 * marginX).coerceAtLeast(1)
        val rangeY   = (rect.height() - 2 * marginY).coerceAtLeast(1)
        val x        = (rect.left + marginX + Random.nextInt(rangeX)).toFloat()
        val y        = (rect.top  + marginY + Random.nextInt(rangeY)).toFloat()
        val duration = (80L + Random.nextInt(71))   // 80–150 ms

        SparkLogger.i(TAG, "tapNode: gesture at (${x}, ${y}) dur=${duration}ms")
        val path    = Path().apply { moveTo(x, y) }
        val stroke  = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                SparkLogger.i(TAG, "tapNode: gesture completed at (${x}, ${y})")
            }
            override fun onCancelled(g: GestureDescription?) {
                SparkLogger.w(TAG, "tapNode: gesture cancelled at (${x}, ${y})")
            }
        }, null)
        return true
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Parsing utilities
    // ──────────────────────────────────────────────────────────────────────────

    /** Extracts the dollar value from strings like "$3.80", "• $19.62", "$19.62 estimate" */
    private fun extractDollar(text: String): Double? =
        Regex("\\$([0-9]+\\.?[0-9]*)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()

    /** Extracts miles from strings like "• 22.6 miles", "22.6 mi" */
    private fun extractMiles(text: String): Double? =
        Regex("([0-9]+\\.?[0-9]*)\\s*mi", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.toDoubleOrNull()

    /** Extracts the first integer from a string like "4 stops" → 4 */
    private fun extractNumber(text: String): Double? =
        Regex("([0-9]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()

    /**
     * Parses "• 1 hr, 1 min", "1 hr 30 min", "45 min", "2 hours" → total minutes as Double.
     */
    private fun parseTimeToMinutes(text: String): Double? {
        val lower = text.lowercase()
        var minutes = 0.0
        var found = false

        Regex("([0-9]+)\\s*(hr|hour)").find(lower)?.let {
            minutes += (it.groupValues[1].toDoubleOrNull() ?: 0.0) * 60.0
            found = true
        }
        Regex("([0-9]+)\\s*min").find(lower)?.let {
            minutes += (it.groupValues[1].toDoubleOrNull() ?: 0.0)
            found = true
        }

        return if (found && minutes > 0) minutes else null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Broadcast to MainActivity
    // ──────────────────────────────────────────────────────────────────────────

    private fun broadcastStatus(message: String) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Data model
// ──────────────────────────────────────────────────────────────────────────────

data class OfferDetails(
    val tipDollars: Double,
    val timeMinutes: Double,
    val estimatedPayDollars: Double?,
    val distanceMiles: Double?,
    val stopCount: Int?       = null,
    // Pay breakdown
    val deliveryPay: Double?  = null,   // "Delivery" row in pay breakdown
    val extraEarnings: Double? = null,  // "Extra Earnings" row (if present)
    // Tip details
    val tipStatus: String?    = null,   // "PENDING" or null if confirmed / no tip
    // Offer details
    val totalItemQty: Int?    = null,   // qty parsed from "X items (Y qty)" at pickup
    val pickupType: String?   = null,   // stop type of the pickup stop
    val pickupStore: String?  = null,   // address_name of the pickup stop
    val tags: List<String>    = emptyList(), // all chip texts (Pharmacy, Shopping, Heavy item…)
    val offerType: String     = "FCFS"  // "JFY" if just-for-you detected, else "FCFS"
) {
    private val miles get() = distanceMiles ?: 0.0
    private val hrs   get() = if (timeMinutes > 0) timeMinutes / 60.0 else 1.0

    /**
     * CAMin — calculated minimum based on this offer's own per-minute rate + $0.30/mile.
     * Formula: 16.9 × 1.2 / 60 × TimeMin + 0.3 × DistanceMi  (CA Prop 22 minimum)
     */
    val caMin: Double
        get() = (16.9 * 1.2 / 60.0) * timeMinutes + (0.3 * miles)

    /**
     * SparkPay = MAX(CAMin, EstimatedTotal − TipPay)
     * Never goes below zero.
     */
    val sparkPay: Double
        get() = maxOf(caMin, (estimatedPayDollars ?: 0.0) - tipDollars.coerceAtLeast(0.0))
            .coerceAtLeast(0.0)

    /** TipPay = customer tip (0 if none). */
    val tipPay: Double get() = tipDollars.coerceAtLeast(0.0)

    /** TotalPay = SparkPay + TipPay */
    val totalPay: Double get() = sparkPay + tipPay

    /** PayHourly = (TotalPay / TimeMin) × 60 */
    val payHourly: Double get() = totalPay / hrs

    /** TipHourly = (TipPay / TimeMin) × 60 */
    val tipHourly: Double get() = tipPay / hrs

    /** DollarsPerMile = TotalPay / DistanceMi */
    val dollarsPerMile: Double get() = if (miles > 0) totalPay / miles else 0.0
}
