plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keystone.feature.vault"
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
    // Hilt + KSP intentionally absent — this module is a placeholder
    // with no @Inject sites. Wire them back in alongside the first
    // real DI client. Until then, skipping KSP saves a full annotation-
    // processing round per build.
}
