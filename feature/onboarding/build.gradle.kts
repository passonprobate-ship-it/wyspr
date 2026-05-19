plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.keystone.feature.onboarding"
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
    implementation(project(":core:sync"))
    implementation(project(":core:database"))
    implementation(project(":core:transport:api"))
    implementation(project(":core:transport:bluetooth"))

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-android-compiler:2.50")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // CameraX for live QR scanning.
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    // ZXing for QR decoding — same lib core:ui uses for rendering.
    implementation("com.google.zxing:core:3.5.2")
}
