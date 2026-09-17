plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.openfit.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.openfit"
        // Wear OS 3+ (Galaxy Watch 4 and newer); Galaxy Watch 7 runs Wear OS 5.
        minSdk = 30
        targetSdk = 34
        versionCode = 14
        versionName = "0.9.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            applicationIdSuffix = ".debug"
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Classic compose material for the M1 UI; Wear-specific components arrive later.
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.health.services)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.guava)
    implementation(libs.androidx.wear.ongoing)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
