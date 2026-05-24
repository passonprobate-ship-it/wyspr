plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.wyspr.feature.onboarding"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:crypto"))
    implementation(project(":core:identity"))
    implementation(project(":core:trust"))
    implementation(project(":core:sync"))
    implementation(project(":core:database"))
    implementation(project(":core:transport:api"))
    implementation(project(":core:transport:bluetooth"))

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.google.dagger:hilt-android:2.55")
    ksp("com.google.dagger:hilt-android-compiler:2.55")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // CameraX for live QR scanning.
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    // ZXing for QR decoding — provided transitively by core:ui as api().

    // NanoHTTPD — single-file embeddable HTTP server. Powers the
    // peer-to-peer APK delivery mini-site: the Inviter hosts a tiny
    // page on the local network, the Invitee's stock camera scanner
    // opens its URL, agrees, and downloads the APK directly from the
    // Inviter's device. No third-party server, no app store, no
    // internet required (same WiFi only in Phase 1).
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // BouncyCastle — used ONLY to build a fresh self-signed X.509
    // certificate per share session so the local HTTPS endpoint
    // satisfies modern browsers' "HTTPS-Only" modes (Brave on
    // Android most aggressively). The certificate's job is to make
    // the browser load the page; security is provided by the SHA-256
    // hash the user verifies on-screen, not by PKI. The bcprov +
    // bcpkix split keeps the X.509 builder pulls minimal.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
