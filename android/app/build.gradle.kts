plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.rjnr.pocketnode"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rjnr.pocketnode"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.5.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only include ARM ABIs — x86_64 is emulator-only and adds ~29 MB.
        // CI's upgrade-smoke harness opts in via BUILD_X86_64=1 (matched in
        // external/ckb-light-client/build-android-jni.sh).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            if (System.getenv("BUILD_X86_64") == "1") {
                abiFilters += "x86_64"
            }
        }

        // ksp { arg("room.schemaLocation", "$projectDir/schemas") } is
        // deliberately omitted, because turning on Room schema export
        // crashes KSP with an AbstractMethodError between Room 2.8.4's
        // bundled kotlinx-serialization-core and the project's
        // kotlinx-serialization-json:1.8.0 (tracked in #149). Re-enable
        // once the dep conflict is resolved.
    }

    testOptions {
        unitTests.all { test ->
            // MockK uses ByteBuddy. On JDK 21+ self-attach is restricted (JEP 451)
            // and once one MockK test runs in a fork the second one fails with
            // "Could not initialize class io.mockk.impl.JvmMockKGateway".
            // The robust fix is to preload byte-buddy-agent as a -javaagent
            // instead of relying on dynamic attach.
            test.doFirst {
                val agentJar = configurations["byteBuddyAgent"]
                    .resolvedConfiguration.resolvedArtifacts
                    .map { it.file }
                    .firstOrNull { it.name.startsWith("byte-buddy-agent") }
                if (agentJar != null) {
                    test.jvmArgs("-javaagent:${agentJar.absolutePath}")
                }
            }
        }
    }

    signingConfigs {
        // Override AGP's auto-generated debug keystore with a checked-in one.
        // The upgrade-smoke harness builds the prev APK on one runner and the
        // PR APK on another; without a stable shared key, the androidTest APK
        // and the target app APK have different signatures, so instrumentation
        // is denied. This keystore is public on purpose (it's a debug key —
        // no security implication; matches Android docs).
        getByName("debug") {
            val sharedDebug = rootProject.file("ci/debug.keystore")
            if (sharedDebug.exists()) {
                storeFile = sharedDebug
                storePassword = "android"
                keyAlias = "smokedebug"
                keyPassword = "android"
            }
            // else: fall through to AGP's default ~/.android/debug.keystore
            // for fresh checkouts that haven't pulled the keystore yet.
        }

        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAliasEnv = System.getenv("KEY_ALIAS")
            val keyPasswordEnv = System.getenv("KEY_PASSWORD")
            if (keystorePath != null && keystorePassword != null && keyAliasEnv != null && keyPasswordEnv != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            } else {
                val missingVars = listOf(
                    "KEYSTORE_PATH" to keystorePath,
                    "KEYSTORE_PASSWORD" to keystorePassword,
                    "KEY_ALIAS" to keyAliasEnv,
                    "KEY_PASSWORD" to keyPasswordEnv
                ).filter { it.second == null }.map { it.first }

                throw GradleException(
                    "Release signing is required. Missing environment variables: ${missingVars.joinToString(", ")}"
                )
            }
        }
    }

    buildTypes {
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

    // Name release APKs like "PocketNode-v1.5.0.apk" so the file a user downloads
    // from a GitHub Release matches the asset URL, and so the built artifact in
    // app/release/ reflects the version without a manual rename step.
    applicationVariants.all {
        if (buildType.name == "release") {
            outputs.all {
                val impl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                impl.outputFileName = "PocketNode-v${defaultConfig.versionName}.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    configurations.all {
        resolutionStrategy {
            // Force use of the listenablefuture capability from guava
            capabilitiesResolution {
                withCapability("com.google.guava:listenablefuture") {
                    select("com.google.guava:guava:0")
                }
            }
        }
    }

}

// Pinned to the version mockk transitively brings in. Preloaded as a -javaagent
// for unit tests so MockK works on JDK 21+ without self-attach.
val byteBuddyAgent: Configuration by configurations.creating

dependencies {
    byteBuddyAgent("net.bytebuddy:byte-buddy-agent:1.14.17")

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.windowsizeclass)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.icons.lucide)
    // Chrome Custom Tabs — keeps explorer-link round-trip inside the app's
    // task so the OEM auth gate doesn't re-engage on return (#138).
    implementation(libs.androidx.browser)
    debugImplementation(libs.androidx.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Security & Crypto
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.secp256k1.kmp.jni.android)
    implementation(libs.kotlin.bip39)

    // CKB SDK
    implementation(libs.ckb.sdk.core)
    implementation(libs.ckb.sdk.utils)
    implementation(libs.bouncycastle)

    // Room (for caching)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.room.paging)

    // Paging 3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // CameraX for camera preview (ZXing decodes frames — no ML Kit native libs)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.accompanist.permissions)

    // QR Code generation (Receive screen)
    implementation(libs.zxing.core)

    // Ktor HTTP client (CoinGecko price fetch)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

tasks.register<Exec>("cargoBuild") {
    workingDir = file("${project.rootDir}/../external/ckb-light-client")
    commandLine("./build-android-jni.sh")
    // 1. Try Android Gradle Plugin's detected NDK
    var ndkDir = try { android.ndkDirectory } catch (e: Exception) { null }

    // 2. Fallback: Check standard macOS NDK location
    if (ndkDir == null || !ndkDir.exists()) {
        val defaultNdkRoot = file("/Users/raheemjnr/Library/Android/sdk/ndk")
        if (defaultNdkRoot.exists()) {
            // Pick the latest valid version (must have toolchains)
            ndkDir = defaultNdkRoot.listFiles()
                ?.filter { it.isDirectory && File(it, "toolchains").exists() }
                ?.sortedByDescending { it.name }
                ?.firstOrNull()
        }
    }

    if (ndkDir != null && ndkDir.exists()) {
        println("Using NDK at: $ndkDir")
        environment("ANDROID_NDK_HOME", ndkDir)
    } else {
        println("WARNING: Could not find Android NDK. Build may fail.")
    }
}

tasks.named("preBuild") {
    dependsOn("cargoBuild")
}
