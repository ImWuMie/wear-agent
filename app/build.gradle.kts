plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
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
}
