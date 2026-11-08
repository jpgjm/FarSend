import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // AGP 9: the KMP library plugin replaces com.android.library (which can no longer be
    // combined with org.jetbrains.kotlin.multiplatform). The android {} block moves into
    // kotlin { androidLibrary {} }.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        // expect/actual classes (FsLock etc.) are Beta; this opts in and silences the warning
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.example.farsend.shared"
        compileSdk = 37
        minSdk = 36

        // Without this, the AGP KMP library plugin packages no resources into the AAR, so the
        // Compose Multiplatform resources (composeResources -> Android assets) are missing at
        // runtime and the first vectorResource() crashes the app with MissingResourceException
        // (this is exactly what happened on the first 1.0.x device install).
        androidResources.enable = true

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            // computer.iroh:iroh (JVM) requires JVM 21+, so desktop targets 21.
            // (Android stays on 17)
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // iOS targets real devices only (SideStore / LiveContainer).
    // Built on a GitHub Actions macOS runner, where Xcode (project.yml
    // preBuildScripts) runs linkReleaseFrameworkIosArm64.
    iosArm64 {
        compilations.getByName("main").cinterops.create("farsendIroh") {
            defFile(project.file("src/nativeInterop/cinterop/farsend_iroh.def"))
            includeDirs(project.file("../native/farsend-iroh/include"))
        }
        binaries.framework {
            baseName = "FarSendShared"
            isStatic = true
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // (Res.drawable.ic_* accessors are generated into the package set in compose.resources below)
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3.expressive) // M3 Expressive (replaces compose.material3; see libs.versions.toml)
            implementation(libs.compose.ui)
            // Screen icons: Material Icons (baseline) vector drawables under composeResources/drawable
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.qrose)
            implementation(libs.jetbrains.lifecycle.runtime.compose) // Device Hub online checks
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // JVM-only code shared by Android and desktop (JVM).
        // Code using the official iroh Kotlin bindings (computer.iroh) and java.io lives here.
        val jvmCommonMain = create("jvmCommonMain") {
            dependsOn(commonMain.get())
            dependencies {
                // Exclude JNA: on Android, iroh-android brings the AAR JNA,
                // and a duplicate jar breaks the build. desktopMain adds the jar for desktop.
                implementation("computer.iroh:iroh:${libs.versions.iroh.get()}") {
                    exclude(group = "net.java.dev.jna", module = "jna")
                }
            }
        }

        androidMain {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.androidx.activity.compose)
                // AAR with libiroh_ffi.so for each ABI and IrohAndroid (Context setup).
                // The classes themselves come from computer.iroh:iroh (jvmCommonMain).
                // jna excluded: iroh-android pulls jna AAR 5.15.0, whose libjnidispatch.so is
                // not 16 KB aligned (lint Aligned16KB); the explicit newer AAR below replaces it
                implementation("computer.iroh:iroh-android:${libs.versions.iroh.get()}") {
                    exclude(group = "net.java.dev.jna", module = "jna")
                }
                implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
            }
        }

        getByName("desktopMain") {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.jna) // needed for native calls of iroh (JVM)
            }
        }

        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources {
    // Package of the generated Res class (Res.drawable.ic_* icon accessors)
    packageOfResClass = "com.example.farsend.resources"
}
