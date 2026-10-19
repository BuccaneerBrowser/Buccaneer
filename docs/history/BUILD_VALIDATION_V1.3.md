# Buccaneer v1.3 Build Validation Contract

This source is based on the more-stable Claude source supplied for this iteration.

- compileSdk: 34
- targetSdk: 33 (left unchanged from the stable source to avoid mixing target-behavior changes into the diagnostic build)
- minSdk: 22 (Android 7/API 24-25 is supported)
- AndroidX WebKit: 1.9.0
- AGP/Kotlin/build dependency stack is unchanged from the supplied stable source.
- No Media3 / ExoPlayer dependency was introduced.
- No compiled SDK/build output is included in the source archive.

Actual Android compilation is expected to be performed by the user's build environment; this environment does not have the complete Android SDK/Gradle toolchain needed to claim a physical APK build.
