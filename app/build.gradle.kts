plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.artem.monthwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.artem.monthwidget"
        minSdk = 26
        targetSdk = 35
        // Every CI build numbers itself; locally it stays 1.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"
        // The commit this build was made from (CI only); the app compares it with the latest release.
        buildConfigField("String", "COMMIT", "\"${System.getenv("GITHUB_SHA")?.take(7) ?: "dev"}\"")
    }

    // A fixed, public "debug" keystore is committed on purpose: every CI build is signed with the
    // same key, so a new APK installs over the previous one without uninstalling. The app holds no secrets.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.glance:glance-appwidget:1.1.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
