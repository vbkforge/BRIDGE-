plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions sets GITHUB_RUN_NUMBER, so every build gets a higher version code
// and installs as an update over the previous one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.vbkforge.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vbkforge.bridge"
        minSdk = 30 // Android 11: needed for accessibility screenshots
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // One fixed key so each new APK installs as an update (keeps your
        // accessibility setting). Can be overridden with repository secrets,
        // see README "Signing key".
        create("bridge") {
            val customStore = System.getenv("BRIDGE_KEYSTORE_FILE")
            storeFile = if (customStore.isNullOrBlank()) file("bridge-debug.keystore") else file(customStore)
            storePassword = System.getenv("BRIDGE_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "bridge-debug"
            keyAlias = System.getenv("BRIDGE_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "bridge"
            keyPassword = System.getenv("BRIDGE_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "bridge-debug"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("bridge")
        }
        debug {
            signingConfig = signingConfigs.getByName("bridge")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Only dependency: OkHttp for the WebSocket connection to the relay.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
