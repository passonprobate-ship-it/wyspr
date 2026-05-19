plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keystone.core.transport.bluetooth"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

android {
    testOptions { unitTests { isReturnDefaultValues = true } }
}

dependencies {
    implementation(project(":core:transport:api"))
    implementation(project(":core:crypto"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
