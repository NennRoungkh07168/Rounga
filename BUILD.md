# Building Feather in Android Studio

## Open it
File → Open → select the `FeatherApp/` folder (the one with `settings.gradle.kts`
in it) → Trust Project.

## One thing you'll need to do on first open: the Gradle wrapper jar
This project ships `gradle/wrapper/gradle-wrapper.properties` (pins Gradle
8.7) but **not** `gradle-wrapper.jar` — that file is a compiled binary and
couldn't be generated in the sandbox this was built in, which has no
network access. You'll see one of two things on first sync:

- Android Studio detects the missing wrapper jar itself and shows a banner
  offering to regenerate it — click it, done.
- If it doesn't, open a terminal in the project root and run:
  ```
  gradle wrapper --gradle-version 8.7
  ```
  (needs a local Gradle install just for this one command — Homebrew:
  `brew install gradle`; Windows: `choco install gradle` or download from
  gradle.org). After that, `./gradlew` works standalone and you don't need
  Gradle installed globally anymore.

Either way, after that: **Sync Now**, then **Run ▶** on the `app`
configuration. Everything else (AGP/Kotlin/Compose versions, SDK levels,
dependencies) is already wired in `build.gradle.kts`.

## What you need installed
- Android Studio Koala (2024.1) or newer
- Android SDK Platform 34, installed via Android Studio's SDK Manager if
  it isn't already (Studio will prompt on first sync if it's missing)
- JDK 17 (Android Studio bundles one — no separate install needed)

## Project layout
```
FeatherApp/
├── settings.gradle.kts, build.gradle.kts, gradle.properties   — root config
├── app/
│   ├── build.gradle.kts        — module config: namespace, SDK levels, deps
│   ├── src/main/AndroidManifest.xml
│   └── src/main/java/feather/
│       ├── core/    — PrecisionUnits, GCodeGenerator, FeatherFile (platform-agnostic)
│       ├── link/    — MachineLink + BLE/Wi-Fi/SD transports, BlePermissions, FeatherViewModel
│       └── ui/      — MainActivity, FeatherScreen (Compose), FeatherTheme
└── windows/
    └── MachineLink.cs          — the .NET counterpart; NOT part of this Gradle
                                   build (no C#/.NET toolchain here) — open it
                                   in Visual Studio as its own WPF/Avalonia
                                   project when you get to the Windows side
```

## Running it
The app that comes up is a minimal but functional exercise of the actual
machine-link protocol, not just a demo shell:
- Drag on the canvas to draw a stroke (committed as a shape in real µm,
  via `BoardCalibration`, not raw pixels).
- Pick a transport (Bluetooth / Wi-Fi / SD card).
- **Start** generates G-code through `GCodeGenerator` and runs it over
  whichever `MachineLink` you picked — same GRBL wait-for-"ok" flow control
  and 0x18 stop byte as a real job, whether or not a real controller is on
  the other end.
- **Stop** calls straight through to the transport's raw stop byte.
- **Save/Load** round-trip through `FeatherFile` to internal app storage
  (`filesDir/drawing.feather`) — a real file picker (SAF) is one of the
  pieces still open, noted in the main README.

Still open, same list as before: the Bluetooth device picker screen (BLE
mode currently takes a pasted MAC address), and the Windows-side
ViewModel equivalent.


## Note on this revision
The Kotlin sources were rewritten without a compiler available, so expect a few
compile errors on first sync (missing imports, Compose opt-ins such as
`@OptIn(ExperimentalMaterial3Api::class)`). Fix them, then test on a real machine
with the spindle/laser disabled first.


## 0.2 notes
- New Gradle dependencies: none. `FileProvider` (camera capture) and `ExifInterface` come from AndroidX core / the platform.
- Manifest: the activity handles configuration changes itself (`configChanges`) and allows all orientations (`fullUser`).
- Tests: `./gradlew test` runs the new unit tests (tracer, G-code analysis, tool/profile copy, file sniffing, shape ops).
- Not compiled where it was written: expect a handful of first-sync fixes.


## Rounga 0.8.0 CAD build

The repository includes a GitHub Actions workflow at `.github/workflows/build.yml`. Push the source to GitHub, then open **Actions → Build Rounga APK** and download the `Rounga-APK` artifact after a successful run. The workflow installs JDK 17 and Gradle 8.7 and runs `gradle assembleDebug`.

## CAD 0.9.0 notes

The CAD workspace now includes rectangular Pocket and axis-aligned Box Boolean Cut/Union/Intersection. Run `gradle testDebugUnitTest` (or `gradle test`) and `gradle assembleDebug` in the GitHub Actions workflow. Boolean CSG is intentionally limited to axis-aligned Box solids; it is not a substitute for OpenCASCADE/FreeCAD B-rep operations.


## Rounga 1.0.0 build from Termux + GitHub

This workflow does not require Android Studio. The included GitHub Actions workflow installs JDK 17 and Gradle 8.7, runs `gradle testDebugUnitTest`, builds `gradle assembleDebug`, and uploads the debug APK as the `Rounga-APK` artifact.

1. Extract this source ZIP over a fresh clone of your repository (keep the `.git` directory).
2. From the repository root in Termux run:
   ```sh
   git add .
   git commit -m "Rounga CAD 1.0.0 interactive sketcher"
   git push origin main
   ```
3. On GitHub open **Actions → Build Rounga APK**. Wait for the workflow to finish.
4. Open the successful run and download **Rounga-APK** under Artifacts. Install the APK on Android and test the CAD tab.

The source ZIP intentionally does not include `gradle-wrapper.jar`; GitHub Actions uses its installed Gradle 8.7 directly, so the workflow does not depend on the wrapper. This environment does not have the Android SDK/Gradle Android toolchain, so the APK has not been built or device-tested here.
