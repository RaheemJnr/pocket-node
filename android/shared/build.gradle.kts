plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
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

    // Room generates the `actual object` for @ConstructedBy, so the module unavoidably has an
    // expect/actual class. Kotlin still flags those as Beta (KT-61573); this is the suppression
    // Room's own KMP setup guide prescribes.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            // Room KMP: one database compiled for Android, iOS device and iOS simulator.
            // This brings the common `androidx.sqlite` API, including SQLiteDriver, but
            // deliberately no driver implementation: each platform supplies its own.
            implementation(libs.room.runtime)
            // Room needs a CoroutineContext for its query dispatcher.
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.secp256k1.kmp)
            implementation(libs.kotlincrypto.blake2)
            // SHA-256 for the BIP-39 checksum, HMAC-SHA-512 for PBKDF2 (#507).
            implementation(libs.kotlincrypto.sha2)
            implementation(libs.kotlincrypto.hmac.sha2)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // runTest, for the suspending Room DAO round trip in iosTest.
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // JNI bindings + .so payload for the Android ABIs.
            implementation(libs.secp256k1.kmp.jni.android)
        }
        iosMain.dependencies {
            // The bundled SQLite driver is iOS-only on purpose. Kotlin/Native has no
            // framework SQLite to fall back on, whereas Android does, and putting this in
            // commonMain pushed ~2.5 MB of libsqliteJni.so into the APK for a database the
            // Android app does not open. Android will pass AndroidSQLiteDriver instead.
            implementation(libs.androidx.sqlite.bundled)
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
            // And again for Argon2id: BouncyCastle's Argon2BytesGenerator is the
            // reference the shared implementation is proved against. It stays an app
            // dependency for now (the BIP-32 code still uses it) but must never reach
            // the shared module's production classpath (#509).
            implementation(libs.bouncycastle.difftest)
        }
    }
}

/**
 * Room's annotation processor has to run once per compilation target: the generated
 * `RoomDatabaseConstructor` actual and the DAO implementations are platform artifacts,
 * not common ones. `kspAndroid` is the configuration the `androidLibrary` target of the
 * `com.android.kotlin.multiplatform.library` plugin creates.
 */
dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
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
