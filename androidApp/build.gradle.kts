plugins {
    alias(libs.plugins.android.application)
    // AGP 9 has built-in Kotlin, so org.jetbrains.kotlin.android is no longer applied
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Name and version come from gradle.properties (0.2.30)
val appName = providers.gradleProperty("farsend.appName").get()
val appVersion = providers.gradleProperty("farsend.version").get()
val appVersionCode = providers.gradleProperty("farsend.versionCode").get().toInt()

android {
    namespace = "com.example.farsend"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.farsend"
        minSdk = 36
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        manifestPlaceholders["appName"] = appName
        ndk {
            // Only real devices (arm64) and emulators (x86_64), to keep the APK small
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                // Desktop natives bundled in computer.iroh:iroh (JVM jar) aren't needed
                "/win32-x86-64/**", "/darwin-aarch64/**", "/linux-x86-64/**", "/linux-aarch64/**",
            )
        }
        jniLibs {
            // Store libiroh_ffi.so uncompressed (no extraction, faster startup)
            useLegacyPackaging = false
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // PoC: release is signed with the debug key too (replace for distribution)
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        // False positive: iroh-ffi generated code calls java.lang.ref.Cleaner behind a guard
        disable += setOf("NewApi")
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    // Keep Compose itself on the same Compose Multiplatform version as shared
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3.expressive) // M3 Expressive (replaces compose.material3; see libs.versions.toml)
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    // Play services pulls androidx.fragment 1.0.0; registerForActivityResult needs 1.3+ (lint error)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx) // lifecycleScope (converting picker results)
    implementation(libs.kotlinx.coroutines.android)

    // For IrohAndroid.installAndroidContext (the AAR also comes in via shared).
    // jna excluded in favor of the 16 KB aligned AAR below (see shared/build.gradle.kts)
    implementation(libs.iroh.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(variantOf(libs.jna) { artifactType("aar") })

    // QR scanning: Google Code Scanner (0.2.38, replaces zxing-android-embedded). The camera screen is
    // Google Play services', so no CAMERA permission
    implementation(libs.play.services.code.scanner)
}
