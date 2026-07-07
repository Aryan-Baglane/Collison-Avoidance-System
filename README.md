# Collision Avoidance System
A mobile Android app (Kotlin) that implements on-device collision avoidance and lane-keeping assist features for demonstration and research. It combines camera and location/map data with routing and lane-keeping logic to detect potential collisions and help keep a vehicle (or simulated vehicle) within lane boundaries.

Repository: Aryan-Baglane/Collison-Avoidance-System

---

## Table of contents
- [Key features](#key-features)
- [Stack / Notable libraries](#stack--notable-libraries)
- [Architecture & high-level design](#architecture--high-level-design)
- [Repository layout](#repository-layout)
- [Build & run (quick start)](#build--run-quick-start)
- [Permissions & environment notes](#permissions--environment-notes)
- [How the app works (runtime flow)](#how-the-app-works-runtime-flow)
- [Development tasks & where to change things](#development-tasks--where-to-change-things)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [License](#license)
- [Acknowledgements](#acknowledgements)

---

## Key features
- Real-time collision detection using camera and map/location data (proof-of-concept level).
- Lane-keep assist components to detect and suggest lane corrections.
- Visual dashboard UI (DashBoardScreen.kt) showing map, alerts, and status.
- Modular code organized into algorithms, model, service and UI packages for easy experimentation.

---

## Stack / Notable libraries
- Language: Kotlin (Android)
- Runtime / Framework: Android SDK (Android app project with Gradle)
- UI: Jetpack Compose (inferred from UI naming like `DashBoardScreen.kt`)
- Notable libraries (inferred from package and file names): AndroidX, Jetpack Compose, Kotlin Coroutines, Google Play Services (Maps / Location) or other map provider (used by `Map.kt`), Android Material components.

---

## Architecture & high-level design
- The app is an Android application with a single APK, containing UI, services, and algorithm modules.
- Main entry points:
  - `MainActivity.kt` — activity bootstrap and lifecycle integration.
  - `App.kt` — application-level initialization (dependency wiring, singletons).
  - `RoutingService.kt` — background/service component for routing or location updates.
  - UI components under `ui/` and `DashBoardScreen.kt` — Compose screens and view state.
- Algorithms live in `algorithms/` and `laneKeepAssist/` — place to implement collision prediction, trajectory planning, lane detection, etc.
- Data structures and DTOs under `model/`. Services for sensor/map integration under `service/`.
- Map and visualization handled by `Map.kt`.

How it fits together: at runtime the app initializes (App.kt), requests necessary permissions (`CameraPermissionCheck.kt`), starts location/map services (RoutingService), streams camera frames and location into algorithm modules (algorithms/, laneKeepAssist/) which produce events/alerts that the UI (DashBoardScreen / Map) displays.

---

## Repository layout
Top-level (annotated):
```
.build.gradle.kts             - root Gradle Kotlin build file (project config)
gradle.properties             - Gradle properties
settings.gradle.kts           - Gradle settings
gradlew / gradlew.bat         - Gradle wrapper
app/                          - Android application module
  build.gradle.kts            - module-level Gradle build configuration
  proguard-rules.pro          - Obfuscation rules for release builds
  src/
    main/
      AndroidManifest.xml     - app manifest and permissions
      java/com/example/...    - Kotlin source files
        App.kt
        MainActivity.kt
        Map.kt
        DashBoardScreen.kt
        CameraPermissionCheck.kt
        RoutingService.kt
        algorithms/           - collision detection / planning logic
        laneKeepAssist/       - lane keeping algorithms
        model/                - data models
        service/              - sensor/location/map integration
        ui/                   - UI components and Compose screens
```

---

## Build & run (quick start)

Prerequisites:
- JDK 11+ (as required by the Gradle configuration)
- Android SDK & Android Studio (recommended) or Android command-line tools
- An Android device or emulator

Commands (from the repository root):
```bash
# Build the debug APK
./gradlew :app:assembleDebug

# Install to a connected device / emulator
./gradlew :app:installDebug

# Run unit tests
./gradlew test

# Run instrumentation tests on a connected device
./gradlew connectedAndroidTest
```

Open the project in Android Studio:
- File → Open... → select the repository root. Let Android Studio sync Gradle and download dependencies.

---

## Permissions & environment notes
This project uses camera and location data. Ensure the following at runtime:
- Grant CAMERA permission (camera usage checked in `CameraPermissionCheck.kt`).
- Grant ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION if the app uses GPS or map features.
- Internet permission if using online map tiles or telemetry.

On Android 6.0+ request these permissions at runtime; CameraPermissionCheck.kt contains helper logic for that.

---

## How the app works (runtime flow)
1. App starts (App.kt), initializes singletons and any DI.
2. MainActivity sets up the Compose UI and attaches the dashboard (DashBoardScreen).
3. App verifies camera and location permissions (`CameraPermissionCheck.kt`). If permissions are missing, prompts the user.
4. `RoutingService.kt` or service-layer components start location updates and map data feeds.
5. `Map.kt` is used to show map tiles, vehicle position, and overlay collision/lane info.
6. Camera frames and location data are fed to algorithms in `algorithms/` and `laneKeepAssist/` which evaluate threats and compute alerts.
7. Alerts and suggested corrective actions are surfaced in the UI (DashBoardScreen) with visual overlays on the map.

---

## Development tasks & where to change things
- Add or tune collision detection algorithms:
  - Edit or add classes in `app/src/main/java/.../algorithms/`
- Change UI / dashboard:
  - Edit `DashBoardScreen.kt` and files under `ui/`.
- Map integration:
  - Inspect and modify `Map.kt` and `service/` implementations for map provider, markers and overlays.
- Permissions & lifecycle:
  - `CameraPermissionCheck.kt` and `MainActivity.kt` handle permission prompts and lifecycle; modify to fit desired UX.
- Background location / routing:
  - `RoutingService.kt` is the place for route calculation, background updates, or simulated driving flows.

---

## Testing
- Unit tests: Use `./gradlew test` to run JVM unit tests (if present).
- Instrumentation / UI tests: Use `./gradlew connectedAndroidTest` with an attached device/emulator.
- Manual testing: Deploy to a device with camera and GPS; use a controlled environment or simulated location to validate algorithms.

---

## Troubleshooting
- Build fails due to missing SDK/platform: Open SDK Manager and install the Android platform version referenced by the module Gradle file.
- Camera permission denied: Check app permissions in device settings; ensure runtime permission flow is handled by `CameraPermissionCheck.kt`.
- Map tiles not visible: Verify API keys or map provider tokens (if the project uses Google Maps / other providers). Check `AndroidManifest.xml` and module Gradle files for API key configuration placeholders.

---

## Contributing
1. Fork the repository.
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Make changes and add tests where possible.
4. Submit a pull request describing the change and reasoning.

Please include:
- Short description of change
- How to run and test locally
- Any required configuration (API keys, env vars)

---

## License
Add a LICENSE file to the repo. If you want a suggested license, `MIT` or `Apache-2.0` are common for this type of project.

---

## Acknowledgements
- This project combines camera, mapping, and algorithmic components; adapt or credit any third-party libraries you include (Google Maps, TensorFlow, OpenCV, etc.) per their licenses.

---

Notes
- Files referenced in this README: `App.kt`, `MainActivity.kt`, `Map.kt`, `DashBoardScreen.kt`, `CameraPermissionCheck.kt`, `RoutingService.kt`, and the packages `algorithms/`, `laneKeepAssist/`, `model/`, `service/`, `ui/` exist in `app/src/main/java/com/example/collisionavoidancesystem/`.
- If you want, I can:
  - generate a smaller "Quick start" README with immediate run steps only,
  - or produce a template LICENSE, CONTRIBUTING.md, or add a sample local-config for map API keys.
