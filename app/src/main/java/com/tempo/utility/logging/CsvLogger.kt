package com.tempo.utility.logging

import android.content.Context
import com.tempo.utility.service.OfferDetails
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CsvLogger — writes one row per offer to a persistent CSV file.
 *
 * File name: spark_offers.csv
 * Location:  /storage/emulated/0/Android/data/com.tempo.utility/files/
 *
 * Thread safety: every public method is @Synchronized on the singleton object
 * to prevent interleaved writes from main/tap/watchdog threads.
 *
 * Columns (19):
 *   Timestamp, EstimatedTotal, DeliveryPay, ExtraEarnings, TipPay,
 *   DistanceMi, TimeMin,
 *   RealMin   = TimeMin + DistanceMi×2
 *   CAMin     = 16.9×1.2/60×TimeMin + 0.3×DistanceMi  (CA Prop 22)
 *   SparkPay  = MAX(CAMin, EstimatedTotal−TipPay)
 *   TotalPay  = SparkPay + TipPay
 *   PayHourly = (SparkPay/RealMin)×60
 *   TipHourly = (TipPay/RealMin)×60
 *   DollarsPerMile = TotalPay/DistanceMi
 *   PickupType, OfferType, PickupStore,
 *   CriteriaResult (PASS|FAIL), ActionResult (PENDING→ACCEPTED|REJECTED|…)
 */
object CsvLogger {

    private const val TAG = "CsvLogger"

    private val HEADER = "Timestamp,EstimatedTotal,DeliveryPay,ExtraEarnings,TipPay," +
          "DistanceMi,TimeMin,RealMin,CAMin,SparkPay,TotalPay," +
          "PayHourly,TipHourly,DollarsPerMile," +
          "PickupType,OfferType,PickupStore,CriteriaResult,ActionResult\n"

    // DateTimeFormatter is thread-safe (unlike SimpleDateFormat).
    private val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private var csvFile: File? = null

    /**
     * Byte offset of the start of the last appended row.
     * updateLastActionResult seeks here instead of reading the whole file,
     * keeping the operation O(lastRowSize) regardless of total file size.
     */
    private var lastRowOffset = -1L

    // ──────────────────────────────────────────────────────────────────────────

    @Synchronized
    fun initialize(context: Context) {
        val dir  = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "spark_offers.csv")
        if (!file.exists()) {
            file.writeText(HEADER)
            SparkLogger.i(TAG, "Created new CSV at ${file.absolutePath}")
        } else {
            SparkLogger.i(TAG, "Appending to existing CSV at ${file.absolutePath}")
        }
        csvFile       = file
        lastRowOffset = -1L
    }

    @Synchronized
    fun append(details: OfferDetails, criteriaResult: String, actionResult: String = "PENDING") {
        val file = csvFile ?: run {
            SparkLogger.e(TAG, "append: CsvLogger not initialized")
            return
        }
        val now = LocalDateTime.now().format(dtf)
        val row = buildString {
            append(now); append(",")
            append(details.estimatedPayDollars?.let { String.format(Locale.US, "%.2f", it) } ?: ""); append(",")
            append(details.deliveryPay?.let  { String.format(Locale.US, "%.2f", it) } ?: ""); append(",")
            append(String.format(Locale.US, "%.2f", details.extraEarnings ?: 0.0)); append(",")
            append(String.format(Locale.US, "%.2f", details.tipPay)); append(",")
            append(details.distanceMiles?.let { String.format(Locale.US, "%.2f", it) } ?: ""); append(",")
            append(String.format(Locale.US, "%.1f", details.timeMinutes)); append(",")
            append(String.format(Locale.US, "%.2f", details.realMin)); append(",")
            append(String.format(Locale.US, "%.2f", details.caMin)); append(",")
            append(String.format(Locale.US, "%.2f", details.sparkPay)); append(",")
            append(String.format(Locale.US, "%.2f", details.totalPay)); append(",")
            append(String.format(Locale.US, "%.2f", details.payHourly)); append(",")
            append(String.format(Locale.US, "%.2f", details.tipHourly)); append(",")
            append(String.format(Locale.US, "%.2f", details.dollarsPerMile)); append(",")
            append(csvQuote(details.pickupType ?: "")); append(",")
            append(details.offerType); append(",")
            append(csvQuote(details.pickupStore ?: "")); append(",")
            append(criteriaResult); append(",")
            append(actionResult)
            append("\n")
        }
        try {
            lastRowOffset = file.length()
            file.appendText(row)
            SparkLogger.i(TAG, "Recorded offer → $row".trimEnd())
        } catch (e: Exception) {
            SparkLogger.e(TAG, "append: failed to write row", e)
        }
    }

    /**
     * Records a home-screen pre-rejected offer — tip/store/type are unknown.
     * ActionResult defaults to PENDING; the REJECTING-state handler overwrites it
     * with REJECTED via updateLastActionResult() once outcome is confirmed.
     */
    @Synchronized
    fun appendHomeCard(
        total: Double,
        distMi: Double?,
        timMin: Double,
        criteriaResult: String,
        actionResult: String = "PENDING"
    ) {
        val file = csvFile ?: run {
            SparkLogger.e(TAG, "appendHomeCard: CsvLogger not initialized")
            return
        }
        val miles     = distMi ?: 0.0
        val realMin   = timMin + miles * 2.0
        val caMin     = (16.9 * 1.2 / 60.0) * timMin + (0.3 * miles)
        // tipPay is unknown on home card; treat as 0 so sparkPay = max(caMin, total)
        val sparkPay  = maxOf(caMin, total)
        // PayHourly uses sparkPay (not raw total) so it stays consistent with SparkPay column
        val payHourly = if (realMin > 0) (sparkPay / realMin) * 60.0 else 0.0
        val perMile   = if (miles  > 0) total / miles else 0.0
        val now = LocalDateTime.now().format(dtf)
        val row = buildString {
            append(now); append(",")
            append(String.format(Locale.US, "%.2f", total)); append(",")
            append(""); append(",")   // DeliveryPay — unknown
            append(""); append(",")   // ExtraEarnings — unknown
            append(""); append(",")   // TipPay — unknown
            append(distMi?.let { String.format(Locale.US, "%.2f", it) } ?: ""); append(",")
            append(String.format(Locale.US, "%.1f", timMin)); append(",")
            append(String.format(Locale.US, "%.2f", realMin)); append(",")
            append(String.format(Locale.US, "%.2f", caMin)); append(",")
            append(String.format(Locale.US, "%.2f", sparkPay)); append(",")
            append(String.format(Locale.US, "%.2f", total)); append(",")   // TotalPay ≈ total (no tip)
            append(String.format(Locale.US, "%.2f", payHourly)); append(",")
            append(""); append(",")   // TipHourly — unknown
            append(String.format(Locale.US, "%.2f", perMile)); append(",")
            append(""); append(",")   // PickupType — unknown
            append(""); append(",")   // OfferType — unknown
            append(""); append(",")   // PickupStore — unknown
            append(criteriaResult); append(",")
            append(actionResult)
            append("\n")
        }
        try {
            lastRowOffset = file.length()
            file.appendText(row)
            SparkLogger.i(TAG, "Recorded home-card offer → $row".trimEnd())
        } catch (e: Exception) {
            SparkLogger.e(TAG, "appendHomeCard: failed to write row", e)
        }
    }

    /**
     * Overwrites the ActionResult field of the last appended row in-place.
     *
     * Seeks directly to [lastRowOffset] via RandomAccessFile so the operation
     * is O(lastRowSize) regardless of total CSV size.  A crash mid-write only
     * risks the last row, not the entire file.
     */
    @Synchronized
    fun updateLastActionResult(result: String) {
        val file = csvFile ?: return
        if (lastRowOffset < 0) {
            SparkLogger.w(TAG, "updateLastActionResult: no row offset recorded — skipping")
            return
        }
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val rowLen = (raf.length() - lastRowOffset).toInt()
                if (rowLen <= 0) return
                raf.seek(lastRowOffset)
                val rowBytes = ByteArray(rowLen)
                raf.readFully(rowBytes)
                val row = String(rowBytes, Charsets.UTF_8).trimEnd('\n', '\r')
                if (row.startsWith("Timestamp")) return   // header only — no data rows yet
                val lastComma = row.lastIndexOf(',')
                if (lastComma < 0) return
                val updatedRow = row.substring(0, lastComma + 1) + result + "\n"
                raf.seek(lastRowOffset)
                raf.setLength(lastRowOffset)              // truncate stale tail
                raf.write(updatedRow.toByteArray(Charsets.UTF_8))
            }
            SparkLogger.i(TAG, "updateLastActionResult: ActionResult=$result")
        } catch (e: Exception) {
            SparkLogger.e(TAG, "updateLastActionResult: failed", e)
        }
    }

    @Synchronized
    fun getFile(): File? = csvFile

    private fun csvQuote(value: String): String {
        if (value.isEmpty()) return value
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
