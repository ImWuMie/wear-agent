import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: CI injects the values as secrets; locally they come from
// keystore.properties (gitignored). Without either, release falls back to the debug key.
val signingProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
    fun env(key: String) = System.getenv(key)?.takeIf { it.isNotBlank() }
    env("SIGNING_STORE_FILE")?.let { setProperty("storeFile", it) }
    env("SIGNING_STORE_PASSWORD")?.let { setProperty("storePassword", it) }
    env("SIGNING_KEY_ALIAS")?.let { setProperty("keyAlias", it) }
    env("SIGNING_KEY_PASSWORD")?.let { setProperty("keyPassword", it) }
}

android {
    namespace = "dev.undefinedteam.wearagent"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.undefinedteam.wearagent"
        minSdk = 30
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        create("release") {
            val storePath = signingProps.getProperty("storeFile")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                // v1 is unnecessary above API 24; v3 enables future key rotation.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Use the real key when configured; otherwise keep the debug key so tag builds
            // (which always set the secrets) and local runs both succeed.
            signingConfig = if (signingProps.getProperty("storeFile") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "META-INF/COPYING"
        }
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.activity.compose)
    implementation(libs.fragment)
    implementation(libs.coroutines)
    implementation(libs.datastore)
    implementation(libs.core.splashscreen)
    implementation(libs.okhttp)
    implementation(libs.play.services.wearable)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
implementation(libs.lifecycle.runtime.compose)
    implementation(libs.commonmark)
    implementation(libs.commonmark.gfm.tables)
    implementation(libs.commonmark.gfm.strikethrough)
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.greek)
    implementation(libs.jlatexmath.cyrillic)
    implementation(libs.wear.remote.interactions)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.org.json)
}
