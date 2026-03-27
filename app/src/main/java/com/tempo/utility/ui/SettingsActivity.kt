package com.tempo.utility.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tempo.utility.R
import com.tempo.utility.logging.SparkLogger
import com.tempo.utility.settings.AppSettings
import com.google.android.material.button.MaterialButton
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.textfield.TextInputEditText

/**
 * SettingsActivity — lets the user configure trip criteria and delay ranges.
 * All values are persisted in SharedPreferences via AppSettings.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var etMinTipAmount:    TextInputEditText
    private lateinit var etMinTipHourly:    TextInputEditText
    private lateinit var etMinTotalPay:     TextInputEditText
    private lateinit var etMinPayHourly:    TextInputEditText
    private lateinit var etMaxDistance:          TextInputEditText
    private lateinit var etMinDollarsPerMile:    TextInputEditText
    private lateinit var swQuickMode:          SwitchCompat
    private lateinit var etQuickMinHourly:     TextInputEditText
    private lateinit var etQuickMinEstTotal: TextInputEditText

    private lateinit var etDetailsDelayMin: TextInputEditText
    private lateinit var etDetailsDelayMax: TextInputEditText
    private lateinit var etAcceptDelayMin:  TextInputEditText
    private lateinit var etAcceptDelayMax:  TextInputEditText
    private lateinit var etRejectDelayMin:  TextInputEditText
    private lateinit var etRejectDelayMax:  TextInputEditText

    private lateinit var btnSave: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etMinTipAmount    = findViewById(R.id.etMinTipAmount)
        etMinTipHourly    = findViewById(R.id.etMinTipHourly)
        etMinTotalPay     = findViewById(R.id.etMinTotalPay)
        etMinPayHourly    = findViewById(R.id.etMinPayHourly)
        etMaxDistance         = findViewById(R.id.etMaxDistance)
        etMinDollarsPerMile   = findViewById(R.id.etMinDollarsPerMile)
        swQuickMode       = findViewById(R.id.swQuickMode)
        etQuickMinHourly  = findViewById(R.id.etQuickMinHourly)
        etQuickMinEstTotal  = findViewById(R.id.etQuickMinEstTotal)

        etDetailsDelayMin = findViewById(R.id.etDetailsDelayMin)
        etDetailsDelayMax = findViewById(R.id.etDetailsDelayMax)
        etAcceptDelayMin  = findViewById(R.id.etAcceptDelayMin)
        etAcceptDelayMax  = findViewById(R.id.etAcceptDelayMax)
        etRejectDelayMin  = findViewById(R.id.etRejectDelayMin)
        etRejectDelayMax  = findViewById(R.id.etRejectDelayMax)

        btnSave = findViewById(R.id.btnSaveSettings)

        loadCurrentValues()

        btnSave.setOnClickListener { saveSettings() }
    }

    private fun loadCurrentValues() {
        etMinTipAmount.setText(String.format("%.2f", AppSettings.minTipAmount))
        etMinTipHourly.setText(String.format("%.2f", AppSettings.minTipHourly))
        etMinTotalPay.setText(String.format("%.2f", AppSettings.minTotalPay))
        etMinPayHourly.setText(String.format("%.2f", AppSettings.minPayHourly))
        etMaxDistance.setText(String.format("%.1f", AppSettings.maxDistance))
        etMinDollarsPerMile.setText(String.format("%.2f", AppSettings.minDollarsPerMile))
        swQuickMode.isChecked = AppSettings.quickModeEnabled
        etQuickMinHourly.setText(String.format("%.0f", AppSettings.quickMinHourly))
        etQuickMinEstTotal.setText(String.format("%.2f", AppSettings.quickMinEstTotal))

        etDetailsDelayMin.setText(AppSettings.delayDetailsMin.toString())
        etDetailsDelayMax.setText(AppSettings.delayDetailsMax.toString())
        etAcceptDelayMin.setText(AppSettings.delayAcceptMin.toString())
        etAcceptDelayMax.setText(AppSettings.delayAcceptMax.toString())
        etRejectDelayMin.setText(AppSettings.delayRejectMin.toString())
        etRejectDelayMax.setText(AppSettings.delayRejectMax.toString())
    }

    private fun saveSettings() {
        val minTipAmount    = etMinTipAmount.text?.toString()?.toFloatOrNull()
        val minTipHourly    = etMinTipHourly.text?.toString()?.toFloatOrNull()
        val minTotalPay     = etMinTotalPay.text?.toString()?.toFloatOrNull()
        val minPayHourly    = etMinPayHourly.text?.toString()?.toFloatOrNull()
        val maxDistance         = etMaxDistance.text?.toString()?.toFloatOrNull()
        val minDollarsPerMile   = etMinDollarsPerMile.text?.toString()?.toFloatOrNull()
        val quickModeEnabled    = swQuickMode.isChecked
        val quickMinHourly      = etQuickMinHourly.text?.toString()?.toFloatOrNull()
        val quickMinEstTotal      = etQuickMinEstTotal.text?.toString()?.toFloatOrNull()

        val detailsMin = etDetailsDelayMin.text?.toString()?.toLongOrNull()
        val detailsMax = etDetailsDelayMax.text?.toString()?.toLongOrNull()
        val acceptMin  = etAcceptDelayMin.text?.toString()?.toLongOrNull()
        val acceptMax  = etAcceptDelayMax.text?.toString()?.toLongOrNull()
        val rejectMin  = etRejectDelayMin.text?.toString()?.toLongOrNull()
        val rejectMax  = etRejectDelayMax.text?.toString()?.toLongOrNull()

        if (listOf(minTipAmount, minTipHourly, minTotalPay, minPayHourly, maxDistance, minDollarsPerMile, quickMinHourly, quickMinEstTotal).any { it == null } ||
            listOf(detailsMin, detailsMax, acceptMin, acceptMax, rejectMin, rejectMax).any { it == null }) {
            Toast.makeText(this, "Please fill in all fields with valid numbers", Toast.LENGTH_LONG).show()
            return
        }

        if (detailsMin!! > detailsMax!!) {
            Toast.makeText(this, "Details delay: min must be ≤ max", Toast.LENGTH_SHORT).show()
            return
        }
        if (acceptMin!! > acceptMax!!) {
            Toast.makeText(this, "Accept delay: min must be ≤ max", Toast.LENGTH_SHORT).show()
            return
        }
        if (rejectMin!! > rejectMax!!) {
            Toast.makeText(this, "Reject delay: min must be ≤ max", Toast.LENGTH_SHORT).show()
            return
        }

        SparkLogger.i("SettingsActivity", "User saved settings — minTipAmt=${minTipAmount!!} minTipHr=${minTipHourly!!}/hr minTotal=${minTotalPay!!} minPayHr=${minPayHourly!!}/hr maxDist=${maxDistance!!}mi min_per_mi=${minDollarsPerMile!!} quickMode=$quickModeEnabled quickMinHr=${quickMinHourly!!}/hr quickMinEstTotal=${quickMinEstTotal!!} delays=[details ${detailsMin!!}-${detailsMax!!}ms, accept ${acceptMin!!}-${acceptMax!!}ms, reject ${rejectMin!!}-${rejectMax!!}ms]")
          AppSettings.save(
              minTipAmount    = minTipAmount!!,
              minTipHourly    = minTipHourly!!,
              minTotalPay     = minTotalPay!!,
              minPayHourly    = minPayHourly!!,
              maxDistance        = maxDistance!!,
            minDollarsPerMile  = minDollarsPerMile!!,
            quickModeEnabled   = quickModeEnabled,
            quickMinHourly     = quickMinHourly!!,
            quickMinEstTotal    = quickMinEstTotal!!,
            delayDetailsMin = detailsMin,
            delayDetailsMax = detailsMax,
            delayAcceptMin  = acceptMin,
            delayAcceptMax  = acceptMax,
            delayRejectMin  = rejectMin,
            delayRejectMax  = rejectMax
        )

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
