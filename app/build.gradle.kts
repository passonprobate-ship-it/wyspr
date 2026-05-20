import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Read signing material from gradle properties first (CI / -P flags),
// then fall back to local.properties (developer machines). The
// local.properties path is gitignored — no secrets ever land in git.
//
// gradle.properties holds empty *placeholders* for the signing keys
// so the schema is documented in-repo. Blank values must NOT win
// over a real value supplied via local.properties or -P, so the
// lookup explicitly treats blanks as absent.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingProp(key: String): String? {
    val fromProject = (project.findProperty(key) as? String)?.takeIf { it.isNotBlank() }
    if (fromProject != null) return fromProject
    return localProps.getProperty(key)?.takeIf { it.isNotBlank() }
}

android {
    namespace = "com.keystone.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.keystone"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.7.0a"
    }

    signingConfigs {
        create("release") {
            // Resolve relative to the project root so `keystore/...` works
            // regardless of which gradle subproject the task was invoked
            // from. Absolute paths from properties are honoured as-is.
            val storePath = signingProp("KEYSTONE_STORE_FILE")
                ?: "keystore/keystone-release.jks"
            storeFile = rootProject.file(storePath)
            storePassword = signingProp("KEYSTONE_STORE_PASSWORD") ?: ""
            keyAlias = signingProp("KEYSTONE_KEY_ALIAS") ?: ""
            keyPassword = signingProp("KEYSTONE_KEY_PASSWORD") ?: ""
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
        allWarningsAsErrors = true
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
            // BouncyCastle 1.78+ ships its multi-release JAR metadata
            // under META-INF/versions/9/...; three BC jars collide on
            // this path during resource merge. Excluding is safe — the
            // OSGi manifest isn't loaded at runtime in our use case.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/versions/**/OSGI-INF/**"
        }
        // kmp-tor's `-exec` Android dependency requires Tor binaries
        // to be extracted to ApplicationInfo.nativeLibraryDir on
        // install — the runtime fork()s them as a real process. The
        // legacy packaging flag tells AGP to put them on disk
        // uncompressed instead of inside the APK's compressed lib
        // section.
        jniLibs {
            useLegacyPackaging = true
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
    implementation(project(":feature:messaging"))
    implementation(project(":feature:monero-wallet"))

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

    // kmp-tor — embedded Tor daemon on Android. Two artifacts: the
    // runtime (Kotlin/coroutines wrapper around tor's control
    // protocol) and the architecture-bundled binary itself. The
    // `-exec` resource extracts a real tor executable to
    // ApplicationInfo.nativeLibraryDir at install time and fork()s
    // it as a subprocess — the runtime talks to it via the local
    // control port. The `-noexec` companion is a thin shim that
    // exposes the same primitives without spawning a process; both
    // must be present for the runtime to pick the right path on
    // each device.
    //
    // Pinned to 2.0.0 + resource 408.13.2 because Keystone runs on
    // Kotlin 1.9.22 (tied to Compose Compiler 1.5.10). Every kmp-tor
    // release after 2.0.x is built against Kotlin 2.1+ and pulls in
    // a kotlin-stdlib whose binary metadata version (2.x) the 1.9
    // compiler cannot read. 2.0.0 was built with 1.9.24, so its
    // class metadata is binary-compatible with our compiler.
    // Re-evaluate the moment Keystone upgrades past Kotlin 2.0.
    implementation("io.matthewnelson.kmp-tor:runtime:2.0.0")
    implementation("io.matthewnelson.kmp-tor:resource-exec-tor:408.13.2")
    implementation("io.matthewnelson.kmp-tor:resource-noexec-tor:408.13.2")

    // BouncyCastle — the HSv3 key derivation needs Ed25519
    // scalarMultBase and SHA3-256, neither of which is in the
    // platform JCE on minSdk 26. :feature:onboarding pulls bcprov
    // transitively, but as `implementation`, so its classes are
    // not visible to :app at compile time — declare it explicitly.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
