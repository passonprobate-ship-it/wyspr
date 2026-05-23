plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.wyspr.core.database"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(project(":core:identity"))
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // SQLCipher — wraps Room via SupportFactory. Symmetric key derived from
    // hardware-keystore identity in core:crypto.
    implementation("net.zetetic:sqlcipher-android:4.6.0@aar")
    // androidx.sqlite gives us SupportSQLiteOpenHelper.Factory — SQLCipher
    // provides a matching factory we hand to Room.databaseBuilder.
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.core:core-ktx:1.12.0")
    // compileOnly so we can annotate entities with @Immutable for
    // Compose stability inference without pulling Compose into the
    // runtime classpath. The annotation is a marker only — no Compose
    // code runs against these entities outside of UI modules that
    // already have the runtime.
    compileOnly("androidx.compose.runtime:runtime:1.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.20")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
