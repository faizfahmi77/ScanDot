# ScanDot

An experimental Android app for capturing and viewing 3D point clouds on a supported phone. Capture runs locally with ARCore depth and camera tracking. The viewer lets you rotate, pan and zoom a saved scan.

The app does **not** reconstruct ordinary video through photogrammetry on the phone. A separate, experimental Python pipeline in this repository uses FFmpeg and COLMAP for video-based photogrammetry on a workstation.

## ScanDot Android app

Requirements: JDK 17, Android SDK 36, and a device supporting ARCore and its Depth API. Google Play Services for AR may need to be installed or updated. A camera permission is required.

```powershell
cd android-app
.\gradlew.bat assembleDebug lintDebug testDebugUnitTest
```

Install the APK at `app/build/outputs/apk/debug/app-debug.apk` as an update to retain existing app data. On the Scans screen, name the scan, tap **Start scan**, and move slowly around the subject in good light. Save the result and open it under **Results**. The experimental fuller-depth option interpolates data and can distort surfaces.

Point clouds may have gaps, tracking drift, noise and missing surfaces. They are not textured meshes or validated measurements. Transparent, reflective or textureless subjects are especially challenging.

## Video reconstruction

The Python pipeline requires Python 3.12 or newer, FFmpeg, FFprobe, and a compatible COLMAP installation. From the repository root:

```powershell
python -m processing.benchmark prepare "path/to/input.mp4" --output "path/to/new-run" --fps 5
python -m processing.benchmark reconstruct "path/to/new-run" --colmap "path/to/colmap.exe" --dense
python -m unittest discover -s tests -v
```

Dense reconstruction requires a compatible COLMAP configuration. See `python -m processing.benchmark --help` and the individual subcommand help for options. A completed process does not guarantee complete or accurate geometry.

## Repository layout

- `android-app/`: Android capture and point-cloud viewer.
- `processing/`: video preparation and reconstruction pipeline.
- `backend/`: optional workstation service.
- `tests/`: Python tests.
- `tools/`: workstation utilities.

Local captures, credentials, generated outputs and personal notes are excluded by `.gitignore`. Review all files and any existing Git history before publishing; ignore rules do not remove files already tracked.
