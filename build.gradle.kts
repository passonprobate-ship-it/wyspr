plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    // Compose Compiler is a first-party Kotlin plugin from 2.0+ — apply
    // it to every module that has `buildFeatures { compose = true }`
    // (drops the per-module composeOptions.kotlinCompilerExtensionVersion
    // line, replaces it with this plugin id in the module's plugins block).
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("com.google.dagger.hilt.android") version "2.55" apply false
    id("com.google.devtools.ksp") version "2.1.20-1.0.32" apply false
}
