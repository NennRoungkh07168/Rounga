# Rounga 3D CAD v1.0.2 build fix

- Fixed Kotlin compilation error in `CadScreen.kt` by importing `feather.core.CadStlExporter`. The exporter is defined in `feather/core/CadModel.kt`.
- Updated GitHub Actions workflow action versions to checkout@v5, setup-java@v5 and setup-gradle@v5.
- No CAD behaviour was otherwise changed.

Build on GitHub Actions with `gradle testDebugUnitTest --stacktrace` and `gradle assembleDebug --stacktrace`.
