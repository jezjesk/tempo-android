# Tempo — Spark Driver Offer Monitor

> **Version: v1.4.0 — 2026-03-25**
> If you see this version line after pulling, your code is up to date.

An Android accessibility service app that monitors the **Spark Driver** app (`com.walmart.sparkdriver`), automatically opens every offer card, evaluates it against your configured criteria, **accepts or rejects it on the detail screen**, and logs everything to a timestamped CSV file for analysis.

---

## How It Works

1. Tempo runs as an Android Accessibility Service in the background.
2. When you open the Spark Driver app and an offer card appears on the home screen, the app taps the card open automatically.
3. It reads the **estimated pay**, **tip**, **time**, **miles**, **stops**, **store**, and **offer type** from the detail screen.
4. It calculates:
   - **SparkPay** = MAX(CAMin, EstTotal − Tip) — where CAMin is Spark's own cost formula floor
   - **TotalPay** = SparkPay + Tip
   - **PayHourly** = TotalPay ÷ hours
   - **TipHourly** = Tip ÷ hours
   - **$/Mile** = TotalPay ÷ distance
5. Every offer is written to a timestamped CSV file with all 16 columns.
6. The offer is checked against your configured criteria (all must pass to accept):
   - Minimum Tip Amount ($)
   - Minimum Tip Hourly ($/hr)
   - Minimum Total Pay ($)
   - Minimum Pay Hourly ($/hr)
   - Minimum Pay Per Mile ($/mi)
   - Maximum Distance (miles)
7. If all criteria pass → the **Accept** button is tapped on the detail screen.
8. If any criterion fails → the **Reject** button is tapped; the confirmation sheet is handled automatically.
9. After accepting, monitoring **pauses automatically** so you can start the trip without interference. Tap **Resume Monitoring** when ready for the next offer.
10. Each app session generates a matched pair of files: `tempo_<timestamp>.log` + `spark_offers_<timestamp>.csv`.

---

## CSV Columns (16)

| Column | Description |
|--------|-------------|
| Timestamp | When the offer was scraped |
| EstimatedTotal | Spark's headline offer total |
| DeliveryPay | Base delivery pay (from breakdown) |
| ExtraEarnings | Bonus/extra earnings (from breakdown) |
| TipPay | Customer tip |
| DistanceMi | Total trip distance in miles |
| TimeMin | Estimated time in minutes |
| CAMin | Calculated minimum (Spark's cost floor) |
| SparkPay | MAX(CAMin, EstTotal − Tip) |
| TotalPay | SparkPay + Tip |
| PayHourly | TotalPay ÷ hours |
| TipHourly | Tip ÷ hours |
| DollarsPerMile | TotalPay ÷ miles |
| PickupType | Shopping / Curbside pickup / etc. |
| OfferType | FCFS (first come first served) or JFY (just for you) |
| PickupStore | Store name |

---

## Pay Formulas

**CAMin** = ((EstimatedTotal − Tip) ÷ 60) × TimeMin + (0.30 × DistanceMi)

**SparkPay** = MAX(CAMin, EstimatedTotal − Tip)  *(never below zero)*

**TotalPay** = SparkPay + Tip

**PayHourly** = (TotalPay ÷ TimeMin) × 60

**TipHourly** = (Tip ÷ TimeMin) × 60

**$/Mile** = TotalPay ÷ DistanceMi

---

## Criteria Defaults

| Setting | Default | Description |
|---------|---------|-------------|
| Min Tip Amount | $2.00 | Minimum tip in dollars |
| Min Tip Hourly | $5.00/hr | Minimum tip earnings per hour |
| Min Total Pay | $8.00 | Minimum TotalPay (SparkPay + Tip) |
| Min Pay Hourly | $15.00/hr | Minimum TotalPay per hour |
| Min Pay Per Mile | $1.00/mi | Minimum TotalPay per mile |
| Max Distance | 20.0 mi | Maximum trip distance |

All thresholds are configurable in the **Settings** screen. Every setting save is logged with all new values.

---

## Tap Delay Defaults

The app randomises taps within a window to avoid robotic timing patterns:

| Action | Default Range |
|--------|--------------|
| Open detail screen | 800 – 2000 ms |
| Tap Accept | 600 – 1500 ms |
| Tap Reject | 600 – 1500 ms |

---

## Active Trip Detection

When an accepted trip is in progress, Spark shows the home card with a **START TRIP** button instead of ACCEPT/REJECT. The app detects this at three layers and pauses monitoring without tapping anything, so you remain in full control of the trip.

---

## Building & Installing

### Requirements

- **Android Studio** (Hedgehog 2023.1.1 or newer)
- **Android SDK** 34 (API 34)
- An Android phone running Android 8.0+ (API 26+)

### Steps

1. Clone or download this repository.
2. Open Android Studio → `File → Open` → select the project folder.
3. Wait for Gradle sync to complete.
4. Connect your phone via USB (enable Developer Mode & USB Debugging).
5. Run:
   ```
   ./gradlew installDebug
   ```
   or click the green ▶ Play button in Android Studio.

---

## First-Time Setup

1. Open **Tempo**.
2. Tap **Settings** to configure your acceptance criteria and tap delays.
3. In the **Monitoring** section, tap **Enable Accessibility Service** → find **Tempo** in the list → toggle it **ON**.
4. Return to the app — the service status will show **Active**.
5. Tap **▶ Resume Monitoring** if not already active.
6. Switch to the **Spark Driver** app. The assistant runs silently in the background.

---

## App Home Screen Layout

1. **⚙ Settings** — configure criteria and delays
2. **Monitoring** — service status, enable button, monitoring status, pause/resume toggle
3. **✉ Email Debug Log** — sends the current session log to `linuxelitist@gmail.com`
4. **✉ Email Offer Records** — sends the current session CSV to `linuxelitist@gmail.com`
5. **How It Works** — quick reference

---

## Project Structure

```
tempo-android/
├── app/src/main/
│   ├── java/com/tempo/utility/
│   │   ├── TempoApplication.kt        ← App entry point; initialises session timestamp
│   │   ├── service/
│   │   │   └── SparkAccessibilityService.kt   ← Core service: detection, scraping, accept/reject
│   │   ├── logging/
│   │   │   ├── SparkLogger.kt                 ← Timestamped .log file writer
│   │   │   └── CsvLogger.kt                   ← 16-column .csv offer recorder
│   │   ├── settings/
│   │   │   └── AppSettings.kt                 ← SharedPreferences: criteria + delay config
│   │   └── ui/
│   │       ├── MainActivity.kt                ← Control panel
│   │       └── SettingsActivity.kt            ← Criteria and delay settings screen
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml
│   │   │   └── activity_settings.xml
│   │   └── xml/
│   │       ├── accessibility_service_config.xml
│   │       └── file_provider_paths.xml
│   └── AndroidManifest.xml
└── README.md
```

---

## Troubleshooting

**App doesn't detect offers**
- Verify the Accessibility Service is enabled (green status in the Monitoring section).
- On Samsung devices go to Settings → Battery → Battery Optimization and exclude Tempo.

**Offer rejected unexpectedly**
- Email the debug log — the REJECT line now shows exactly which criterion failed and what your current thresholds were, e.g.:
  `criteria check → REJECT | tipPay=5.74 < min=6.00 | settings: minTip=6.00 ...`

**Offer accepted unexpectedly**
- Check your criteria in Settings. The ACCEPT line logs all values so you can compare against your thresholds.

**"Offer unavailable" after tapping Accept**
- This is a Spark race condition where the offer is assigned despite the modal. The app detects the active trip card that appears immediately after and pauses monitoring correctly.

---

## Privacy

- Tempo only reads the screen when **Spark Driver** is in the foreground.
- All data (logs, CSV) stays on-device until you email it.
- The app does not interact with Spark's servers in any way.
