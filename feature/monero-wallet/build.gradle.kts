plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// :feature:monero-wallet is the Monero wallet utility module. It is
// the only Keystone module that intentionally depends on real
// cryptocurrency code, and the only module whose distribution
// licence concerns shift the combined APK to GPLv3 once the
// monerujo JNI binding lands (v0.7.0b). The rest of Keystone is
// Apache-2.0 and may be reused under those terms.
//
// Architecture: remote-node-over-Tor only. The module never speaks
// directly to the public internet — every byte goes through the
// embedded Tor SOCKS proxy provided by TorBackend. See
// NEXT-STEPS.md §10 for the threat model and SECURITY-MODEL.md §11
// (to be added) for the formal write-up.

android {
    namespace = "com.keystone.feature.monero"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:crypto"))
    implementation(project(":core:identity"))
    implementation(project(":core:transport:api"))

    // Compose / nav / lifecycle
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // material-icons-extended is exposed transitively by `:core:ui` as api().
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.core:core-ktx:1.12.0")

    // DI
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-android-compiler:2.50")

    // Coroutines for the RPC client.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
