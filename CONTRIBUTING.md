# Contributing

Issues and pull requests are welcome. For substantial changes, open an issue first to discuss the problem and proposed approach.

## Development checks

Android requires JDK 17 and Android SDK 36. From `android-app/`, run:

```sh
./gradlew assembleDebug lintDebug testDebugUnitTest
```

On Windows, use `gradlew.bat` instead. To run the Python tests from the repository root, use Python 3.12:

```sh
python -m pip install -r backend/requirements-lock.txt
python -m unittest discover -s tests -v
```

Use a virtual environment for Python dependencies. Tests exercise processing boundaries with synthetic fixtures; they do not establish geometric accuracy or cover live capture on a device.

## Pull requests

- Explain the problem, the change and how it was verified. Include device and Android version for capture or rendering changes.
- Add tests for behavior changes where practical. For visual or hardware-dependent changes, describe the manual checks and their limitations.
- Keep scans, videos, credentials, generated models and machine-specific files out of commits. Do not share recordings without permission.
- Keep capture modes and measurement limitations accurate in the interface and documentation.

By submitting a contribution, you agree that it may be distributed under the repository's [MIT license](LICENSE).
