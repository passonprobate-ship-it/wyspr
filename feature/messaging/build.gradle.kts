plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.keystone.feature.messaging"
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
    implementation(project(":core:ui"))
    implementation(project(":core:crypto"))
    implementation(project(":core:identity"))
    implementation(project(":core:trust"))
    implementation(project(":core:database"))
    implementation(project(":core:transport:api"))
    implementation(project(":core:transport:bluetooth"))

    // libsodium for Ed25519 → X25519 conversion in the channel-
    // binding step of the sync handshake. The same primitive
    // core:trust uses for InvitationCertificate verification.
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-android-compiler:2.50")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // material-icons-extended is exposed transitively by `:core:ui` as api().
    implementation("androidx.compose.foundation:foundation")

    // CameraX + ZXing for the mailbox QR scanner. Same versions
    // feature:onboarding pins; if we ever extract the camera shell
    // into a shared module these go with it.
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("com.google.zxing:core:3.5.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
}
