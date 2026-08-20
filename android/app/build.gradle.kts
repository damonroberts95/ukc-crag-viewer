plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI stamps the version from the git tag. The in-app updater compares the tag
// on the latest GitHub release against versionName, so a build published as
// v1.1 while still calling itself 1.0 would offer itself as an update forever.
// Local builds keep the checked-in values.
val appVersionName: String = System.getenv("VERSION_NAME") ?: "1.0"
val appVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

// Signing material never lives in the repo. CI writes the keystore out of a
// secret and points KEYSTORE_FILE at it. With no keystore present — which is
// every local build — release falls back to the debug key, so assembleRelease
// still works here without anyone needing to hold the real one.
val releaseKeystore = (System.getenv("KEYSTORE_FILE") ?: "release.keystore")
    .let { rootProject.file(it) }
    .takeIf { it.exists() }

android {
    namespace = "dr.ukccrags"
    compileSdk = 36

    defaultConfig {
        applicationId = "dr.ukccrags"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }

            // v2 alone was all the 1.0 APK carried. v3 is what lets this key be
            // rotated later without every install breaking, and v4 is what the
            // installer wants for a fast incremental update. v1 is JAR signing,
            // superseded well before this app's minimum of API 26.
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    // OpenStreetMap tiles: no API key, and it caches what it draws, which is
    // what lets the map work at a crag with no signal.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
