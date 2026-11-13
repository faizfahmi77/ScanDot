# ScanDot Android app

Build with JDK 17 and Android SDK 36:

```powershell
.\gradlew.bat assembleDebug lintDebug testDebugUnitTest
```

The debug APK is generated under `app/build/outputs/apk/debug/`. Install it as an update to preserve existing app data. ARCore Depth support and camera permission are required for capture; Google Play Services for AR may need setup.

Capture uses locally estimated depth and camera tracking. The result is a bounded point cloud, not a textured mesh or a complete photogrammetric reconstruction. Saved models remain in private app storage; uninstalling the app removes them. The optional fuller-depth mode interpolates and may distort surfaces. Accuracy, coverage and device performance remain unvalidated.
