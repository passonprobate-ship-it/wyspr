plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keystone.core.transport.api"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core:identity"))
    // BLAKE2s for ServiceUuid (PROTOCOLS.md §6). Pulls in noise-java
    // transitively, which is already on the app classpath via core:crypto.
    implementation(project(":core:crypto"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
