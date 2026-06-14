# Health Connect to Garmin Sync

This bridge syncs only weight and blood pressure from Android Health Connect to Garmin Connect.

It is split into two small pieces:

- `android-exporter`: Android app that reads Health Connect and exports JSON.
- `garmin-uploader`: Python script that uploads that JSON to Garmin Connect.

Garmin Connect does not currently read Health Connect weight or blood pressure directly, so this tool exports the records and uploads them as manual Garmin entries.

## Data Flow

```text
Health Connect
  -> Android exporter JSON
  -> Python Garmin uploader
  -> Garmin Connect
```

## JSON Format

```json
{
  "exported_at": "2026-06-14T08:00:00Z",
  "weight": [
    {
      "time": "2026-06-14T07:30:00+08:00",
      "kg": 72.5
    }
  ],
  "blood_pressure": [
    {
      "time": "2026-06-14T07:35:00+08:00",
      "systolic": 120,
      "diastolic": 80,
      "pulse": 65
    }
  ]
}
```

Blood pressure upload to Garmin requires pulse. The Android exporter looks for a nearby Health Connect heart-rate sample. If no pulse is found, that blood-pressure record is exported with `"pulse": null`, and the Python uploader skips it by default.

## Android Exporter

Open `android-exporter` in Android Studio, install it on the Android phone that has Health Connect data, then:

1. Tap `Grant Health Connect Permissions`.
2. Tap `Export Last 30 Days`.
3. Share or save the generated JSON file.

Required Health Connect read permissions:

- Weight
- Blood pressure
- Heart rate, used only to find pulse near a blood-pressure reading

## Garmin Uploader

Install dependencies:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

Set credentials:

```powershell
$env:GARMIN_EMAIL = "you@example.com"
$env:GARMIN_PASSWORD = "your-password"
```

Upload:

```powershell
python upload_to_garmin.py exported-health-data.json
```

Dry run:

```powershell
python upload_to_garmin.py exported-health-data.json --dry-run
```

If your Garmin account uses MFA, the script will ask for the one-time code.

## Duplicate Handling

The uploader keeps a local `uploaded-records.json` state file and records a stable hash for each uploaded item. This prevents re-uploading the same exported record if you run the script again.

Use `--state path\to\file.json` to choose another state file.

## Notes

- This uses the unofficial `python-garminconnect` library and Garmin's private endpoints. Garmin can change those endpoints.
- Weight is uploaded in kilograms.
- Blood pressure is uploaded as manual Garmin entries.
- No Garmin password is stored by this project. Garmin tokens may be cached by `python-garminconnect` in its own token store.
