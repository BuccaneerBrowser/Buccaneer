plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from the GitHub Actions secrets (see .github/workflows/build.yml).
// Debug builds need no signing config at all.
val isReleaseBuild = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
val releaseStorePassword = if (isReleaseBuild) {
    System.getenv("BUCCANEER_KEYSTORE_PASSWORD")
        ?: throw org.gradle.api.GradleException(
            "BUCCANEER_KEYSTORE_PASSWORD environment variable is required for release builds")
} else null
val releaseKeyPassword = if (isReleaseBuild) {
    System.getenv("BUCCANEER_KEY_PASSWORD")
        ?: throw org.gradle.api.GradleException(
            "BUCCANEER_KEY_PASSWORD environment variable is required for release builds")
} else null

android {
    namespace = "com.buccaneer.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.buccaneer.browser"
        // 22 = Android 5.1, the oldest Fire OS (Fire OS 5) that Fire TV Sticks ship with.
        minSdk = 22
        targetSdk = 33
        versionCode = 23
        versionName = "2.3"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../buccaneer-release.keystore")
            keyAlias = "buccaneer"
            storePassword = releaseStorePassword
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Used only for WebViewCompat.addDocumentStartJavaScript (runs the Popup Guard script
    // before any page script, in every frame). The app falls back gracefully without it.
    implementation("androidx.webkit:webkit:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
}

// Robolectric's API-34 file descriptor bridge needs this on JDK 17; runtime APK unchanged.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
}
