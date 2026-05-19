plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keystone.core.ui"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    api(composeBom)
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-graphics")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")
    api("androidx.compose.ui:ui-tooling-preview")
    debugApi("androidx.compose.ui:ui-tooling")

    // QR rendering — pure-Java core (no Android dep), kept here so feature
    // modules don't each pull it in. Scanning needs zxing-android-embedded;
    // that lives in feature:onboarding because it pulls in camera deps.
    api("com.google.zxing:core:3.5.3")
}
