plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// :feature:monero-wallet is the Monero wallet utility module. It is
// the only Wyspr module that intentionally depends on real
// cryptocurrency code, and the only module whose distribution
// licence concerns shift the combined APK to GPLv3 once the
// monerujo JNI binding lands (v0.7.0b). The rest of Wyspr is
// Apache-2.0 and may be reused under those terms.
//
// Architecture: remote-node-over-Tor only. The module never speaks
// directly to the public internet — every byte goes through the
// embedded Tor SOCKS proxy provided by TorBackend. See
// NEXT-STEPS.md §10 for the threat model and SECURITY-MODEL.md §11
// (to be added) for the formal write-up.

android {
    namespace = "com.wyspr.feature.monero"
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
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:crypto"))
    implementation(project(":core:identity"))
    implementation(project(":core:database"))
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
    implementation("com.google.dagger:hilt-android:2.55")
    ksp("com.google.dagger:hilt-android-compiler:2.55")

    // Coroutines (used by mollyim's suspend / Flow API).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // The Monero wallet engine — mollyim's modern Kotlin SDK.
    // Sandboxed native libwallet2 runs in a zero-privilege isolated
    // Android process. Suspend / Flow public API. Pluggable HTTP
    // stack — we route its network calls through TorBackend.socksPort
    // via [TorRoutedHttpStack] so the light-wallet-server traffic
    // never leaves our embedded Tor circuits.
    //
    // GPLv3 — shifts the combined APK licence as noted in the module
    // kdoc above. Unblocked by the Kotlin 2.1.20 toolchain bump
    // (commit f7652c4) — earlier 1.9.22 compiler couldn't read this
    // dep's strict kotlin-stdlib:2.1.0 metadata.
    implementation("im.molly:monero-wallet-sdk:1.0.0")

    // OkHttp 4.10.0 — version-aligned with mollyim's transitive (its
    // public API surfaces OkHttpClient on MoneroNodeClient.setHttpClient
    // and singleNodeClient(), so we need our own consumer reference
    // at the same version to avoid classloader-visibility mismatches).
    implementation("com.squareup.okhttp3:okhttp:4.10.0")

    // mollyim transitively pulls androidx.core 1.15.0 which requires
    // compileSdk 35; we're on compileSdk 34 (AGP 8.2.0's max). Force
    // the version Wyspr uses everywhere else. Safe because mollyim
    // only references core's RangeNotificationCompat-style stuff at
    // runtime, all of which exists in 1.12.0.
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.core:core-ktx:1.12.0")

    // EncryptedSharedPreferences for the seed-backup-acked flag.
    // Same version pin the rest of the codebase uses.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}

// Constrain transitive androidx.core to 1.12.x — see above.
configurations.configureEach {
    resolutionStrategy {
        force("androidx.core:core:1.12.0")
        force("androidx.core:core-ktx:1.12.0")
    }
}
