plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    androidLibrary {
        namespace = "com.rjnr.pocketnode.shared"
        compileSdk = 36
        minSdk = 26
        withHostTestBuilder {}

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            baseName = "PocketNodeCore"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.secp256k1.kmp)
            implementation(libs.kotlincrypto.blake2)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            // JNI bindings + .so payload for the Android ABIs.
            implementation(libs.secp256k1.kmp.jni.android)
        }
        getByName("androidHostTest").dependencies {
            // JVM JNI payload so host-side unit tests can call libsecp256k1.
            implementation(libs.secp256k1.kmp.jni.jvm)
            // Differential tests only: the CKB Java SDK is the reference these
            // primitives are proved against. It must never appear on a
            // production classpath (see grep guard in #454).
            implementation(libs.ckb.sdk.core.difftest)
            // `utils` (Blake2b, ECKeyPair, Sign, Numeric) is only a runtime dep of
            // `core`, so the differential tests have to ask for it by name.
            implementation(libs.ckb.sdk.utils.difftest)
        }
    }
}
