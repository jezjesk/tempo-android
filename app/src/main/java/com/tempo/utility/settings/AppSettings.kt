package com.tempo.utility.settings

import android.content.Context
import android.content.SharedPreferences
import com.tempo.utility.service.OfferDetails

/**
 * AppSettings — persisted user configuration.
 *
 * Initialised once in TempoApplication.onCreate().
 * Values survive app restarts automatically via SharedPreferences.
 */
object AppSettings {

    private const val PREFS_NAME = "tempo_settings"

    // ── Keys ──────────────────────────────────────────────────────────────────
    const val KEY_MIN_TIP_AMOUNT    = "min_tip_amount"
    const val KEY_MIN_TIP_HOURLY    = "min_tip_hourly"
    const val KEY_MIN_TOTAL_PAY     = "min_total_pay"
    const val KEY_MIN_PAY_HOURLY    = "min_pay_hourly"
    const val KEY_MAX_DISTANCE          = "max_distance"
    const val KEY_MIN_DOLLARS_PER_MILE  = "min_dollars_per_mile"
    const val KEY_QUICK_MODE_ENABLED   = "quick_mode_enabled"
    const val KEY_QUICK_MIN_HOURLY     = "quick_min_hourly"

    const val KEY_DELAY_DETAILS_MIN = "delay_details_min"
    const val KEY_DELAY_DETAILS_MAX = "delay_details_max"
    const val KEY_DELAY_ACCEPT_MIN  = "delay_accept_min"
    const val KEY_DELAY_ACCEPT_MAX  = "delay_accept_max"
    const val KEY_DELAY_REJECT_MIN  = "delay_reject_min"
    const val KEY_DELAY_REJECT_MAX  = "delay_reject_max"

    // ── Defaults ──────────────────────────────────────────────────────────────
    const val DEFAULT_MIN_TIP_AMOUNT    = 2.0f
    const val DEFAULT_MIN_TIP_HOURLY    = 5.0f
    const val DEFAULT_MIN_TOTAL_PAY     = 8.0f
    const val DEFAULT_MIN_PAY_HOURLY    = 15.0f
    const val DEFAULT_MAX_DISTANCE          = 20.0f
    const val DEFAULT_MIN_DOLLARS_PER_MILE  = 1.00f
    const val DEFAULT_QUICK_MIN_HOURLY     = 50.0f

    const val DEFAULT_DELAY_DETAILS_MIN = 800L
    const val DEFAULT_DELAY_DETAILS_MAX = 2000L
    const val DEFAULT_DELAY_ACCEPT_MIN  = 600L
    const val DEFAULT_DELAY_ACCEPT_MAX  = 1500L
    const val DEFAULT_DELAY_REJECT_MIN  = 600L
    const val DEFAULT_DELAY_REJECT_MAX  = 1500L

    // ── Internal state ────────────────────────────────────────────────────────
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // ── Trip Criteria reads ───────────────────────────────────────────────────
    val minTipAmount: Double get() = p().getFloat(KEY_MIN_TIP_AMOUNT, DEFAULT_MIN_TIP_AMOUNT).toDouble()
    val minTipHourly: Double get() = p().getFloat(KEY_MIN_TIP_HOURLY, DEFAULT_MIN_TIP_HOURLY).toDouble()
    val minTotalPay:  Double get() = p().getFloat(KEY_MIN_TOTAL_PAY,  DEFAULT_MIN_TOTAL_PAY).toDouble()
    val minPayHourly: Double get() = p().getFloat(KEY_MIN_PAY_HOURLY, DEFAULT_MIN_PAY_HOURLY).toDouble()
    val maxDistance:        Double get() = p().getFloat(KEY_MAX_DISTANCE,         DEFAULT_MAX_DISTANCE).toDouble()
    val minDollarsPerMile:  Double get() = p().getFloat(KEY_MIN_DOLLARS_PER_MILE, DEFAULT_MIN_DOLLARS_PER_MILE).toDouble()
    val quickModeEnabled: Boolean get() = p().getBoolean(KEY_QUICK_MODE_ENABLED, false)
    val quickMinHourly:   Double  get() = p().getFloat(KEY_QUICK_MIN_HOURLY, DEFAULT_QUICK_MIN_HOURLY).toDouble()

    // ── Delay Criteria reads ──────────────────────────────────────────────────
    val delayDetailsMin: Long get() = p().getLong(KEY_DELAY_DETAILS_MIN, DEFAULT_DELAY_DETAILS_MIN)
    val delayDetailsMax: Long get() = p().getLong(KEY_DELAY_DETAILS_MAX, DEFAULT_DELAY_DETAILS_MAX)
    val delayAcceptMin:  Long get() = p().getLong(KEY_DELAY_ACCEPT_MIN,  DEFAULT_DELAY_ACCEPT_MIN)
    val delayAcceptMax:  Long get() = p().getLong(KEY_DELAY_ACCEPT_MAX,  DEFAULT_DELAY_ACCEPT_MAX)
    val delayRejectMin:  Long get() = p().getLong(KEY_DELAY_REJECT_MIN,  DEFAULT_DELAY_REJECT_MIN)
    val delayRejectMax:  Long get() = p().getLong(KEY_DELAY_REJECT_MAX,  DEFAULT_DELAY_REJECT_MAX)

    // ── Random delay helpers ──────────────────────────────────────────────────
    fun randomClickToDetailsDelay(): Long = (delayDetailsMin..delayDetailsMax).random()
    fun randomClickAcceptDelay():    Long = (delayAcceptMin..delayAcceptMax).random()
    fun randomClickRejectDelay():    Long = (delayRejectMin..delayRejectMax).random()

    // ── Criteria evaluation ───────────────────────────────────────────────────
    /**
     * Returns true only if the offer passes ALL configured thresholds.
     * Every criterion is evaluated literally (0 means 0, not "skip").
     */
    fun meetsAllCriteria(details: OfferDetails): Boolean {
        if (details.tipPay    < minTipAmount) return false
        if (details.tipHourly < minTipHourly) return false
        if (details.totalPay  < minTotalPay)  return false
        if (details.payHourly < minPayHourly) return false
        val dist = details.distanceMiles
        if (dist != null && dist > maxDistance) return false
        if (details.dollarsPerMile < minDollarsPerMile) return false
        return true
    }

    // ── Batch save ────────────────────────────────────────────────────────────
    fun save(
        minTipAmount:    Float,
        minTipHourly:    Float,
        minTotalPay:     Float,
        minPayHourly:    Float,
        maxDistance:        Float,
        minDollarsPerMile:  Float,
        quickModeEnabled:   Boolean,
        quickMinHourly:     Float,
        delayDetailsMin: Long,
        delayDetailsMax: Long,
        delayAcceptMin:  Long,
        delayAcceptMax:  Long,
        delayRejectMin:  Long,
        delayRejectMax:  Long
    ) {
        p().edit().apply {
            putFloat(KEY_MIN_TIP_AMOUNT,    minTipAmount)
            putFloat(KEY_MIN_TIP_HOURLY,    minTipHourly)
            putFloat(KEY_MIN_TOTAL_PAY,     minTotalPay)
            putFloat(KEY_MIN_PAY_HOURLY,    minPayHourly)
            putFloat(KEY_MAX_DISTANCE,         maxDistance)
            putFloat(KEY_MIN_DOLLARS_PER_MILE, minDollarsPerMile)
            putBoolean(KEY_QUICK_MODE_ENABLED, quickModeEnabled)
            putFloat(KEY_QUICK_MIN_HOURLY, quickMinHourly)
            putLong(KEY_DELAY_DETAILS_MIN,  delayDetailsMin)
            putLong(KEY_DELAY_DETAILS_MAX,  delayDetailsMax)
            putLong(KEY_DELAY_ACCEPT_MIN,   delayAcceptMin)
            putLong(KEY_DELAY_ACCEPT_MAX,   delayAcceptMax)
            putLong(KEY_DELAY_REJECT_MIN,   delayRejectMin)
            putLong(KEY_DELAY_REJECT_MAX,   delayRejectMax)
            apply()
        }
    }

    private fun p(): SharedPreferences =
        checkNotNull(prefs) { "AppSettings.init(context) must be called before reading settings" }
}
