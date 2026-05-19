plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.keystone.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.keystone"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                project.findProperty("KEYSTONE_STORE_FILE") as? String
                    ?: "keystore/keystone-release.jks"
            )
            storePassword = project.findProperty("KEYSTONE_STORE_PASSWORD") as? String ?: ""
            keyAlias = project.findProperty("KEYSTONE_KEY_ALIAS") as? String ?: ""
            keyPassword = project.findProperty("KEYSTONE_KEY_PASSWORD") as? String ?: ""
        }
    }

    buildTypes {
        debug {
            // Debug builds still ship hardened crypto. The only delta is logging.
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Fail the build on warnings; Keystone runs hot, no laxity.
        allWarningsAsErrors = false  // flip to true once stubs are filled
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
        }
    }
}

dependencies {
    // ──── Keystone modules ──────────────────────────────────────────────
    implementation(project(":core:crypto"))
    implementation(project(":core:identity"))
    implementation(project(":core:trust"))
    implementation(project(":core:transport:api"))
    implementation(project(":core:transport:bluetooth"))
    implementation(project(":core:transport:wifidirect"))
    implementation(project(":core:sync"))
    implementation(project(":core:database"))
    implementation(project(":core:ui"))
    implementation(project(":core:currency"))

    implementation(project(":feature:onboarding"))
    implementation(project(":feature:vault"))
    implementation(project(":feature:marketplace"))
    implementation(project(":feature:coordination"))
    implementation(project(":feature:directory"))

    // ──── Compose / UI ──────────────────────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ──── DI ────────────────────────────────────────────────────────────
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // ──── Core ──────────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Hilt provider methods need libsodium types on the classpath even
    // though no app-level code touches them directly.
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Biometric prompt — app-level lock on launch.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
