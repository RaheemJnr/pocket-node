plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
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
            implementation(libs.kotlinx.serialization.json)
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
            // JUnit 4 + MockK: the TransactionBuilder suites moved here from the
            // app module unchanged apart from dropping a vestigial Robolectric
            // runner (#455). Same coordinates the app module uses.
            implementation("junit:junit:4.13.2")
            implementation(libs.mockk)
            // Differential tests only: the CKB Java SDK is the reference these
            // primitives are proved against. It must never appear on a shipping
            // classpath — :app's `checkNoCkbSdkOnRuntimeClasspath` task enforces
            // that and runs as part of `check` (#454).
            implementation(libs.ckb.sdk.core.difftest)
            // `utils` (Blake2b, ECKeyPair, Sign, Numeric) is only a runtime dep of
            // `core`, so the differential tests have to ask for it by name.
            implementation(libs.ckb.sdk.utils.difftest)
        }
    }
}

// MockK uses ByteBuddy. On JDK 21+ self-attach is restricted (JEP 451), so the agent is
// preloaded with -javaagent instead of relying on dynamic attach. Mirrors the same
// workaround in app/build.gradle.kts.
val byteBuddyAgent: Configuration by configurations.creating

dependencies {
    byteBuddyAgent("net.bytebuddy:byte-buddy-agent:1.14.17")
}

tasks.withType<Test>().configureEach {
    doFirst {
        val agentJar = byteBuddyAgent.resolvedConfiguration.resolvedArtifacts
            .map { it.file }
            .firstOrNull { it.name.startsWith("byte-buddy-agent") }
        if (agentJar != null) {
            jvmArgs("-javaagent:${agentJar.absolutePath}")
        }
    }
}
