plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wyspr.core.crypto"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isReturnDefaultValues = true } }
}

dependencies {
    // Noise protocol — rweather/noise-java has no tagged releases on JitPack,
    // so we pin a commit hash. If the JitPack repo refuses on a future build
    // we have a hand-rolled fallback in Blake2s.kt + NoiseSessionImpl uses
    // libsodium primitives; the only piece this dep contributes is the XX
    // state machine itself.
    implementation("com.github.rweather:noise-java:49377b6")
    // libsodium binding
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.core:core-ktx:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.20")
}
