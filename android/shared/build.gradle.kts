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
            // SHA-256 for the BIP-39 checksum, HMAC-SHA-512 for PBKDF2 (#507).
            implementation(libs.kotlincrypto.sha2)
            implementation(libs.kotlincrypto.hmac.sha2)
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
            implementation(libs.junit)
            implementation(libs.mockk)
            // Differential tests only: the CKB Java SDK is the reference these
            // primitives are proved against. It must never appear on a shipping
            // classpath — :app's `checkNoCkbSdkOnRuntimeClasspath` task enforces
            // that and runs as part of `check` (#454).
            implementation(libs.ckb.sdk.core.difftest)
            // `utils` (Blake2b, ECKeyPair, Sign, Numeric) is only a runtime dep of
            // `core`, so the differential tests have to ask for it by name.
            implementation(libs.ckb.sdk.utils.difftest)
            // Same arrangement for BIP-39: kotlin-bip39 is JVM-only, so it is the
            // reference `Bip39` is proved against here and ships nowhere (#507).
            implementation(libs.kotlin.bip39.difftest)
        }
    }
}

// MockK uses ByteBuddy. On JDK 21+ self-attach is restricted (JEP 451), so the agent is
// preloaded with -javaagent instead of relying on dynamic attach. Mirrors the same
// workaround in app/build.gradle.kts.
val byteBuddyAgent: Configuration by configurations.creating

dependencies {
    byteBuddyAgent(libs.bytebuddy.agent)
}

/**
 * Supplies the -javaagent flag at execution time from a lazily resolved [FileCollection].
 * Resolving the configuration inside a `doFirst` instead (via `resolvedConfiguration`) reaches
 * back into the Project from a task action, which breaks the configuration cache.
 */
class ByteBuddyAgentArgumentProvider(
    @get:Classpath val agentJar: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-javaagent:${agentJar.singleFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(ByteBuddyAgentArgumentProvider(byteBuddyAgent))
}
