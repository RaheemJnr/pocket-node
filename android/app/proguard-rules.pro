# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/raheemjnr/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Keep the JNI bridge class and all its members (external funs)
-keep class com.nervosnetwork.ckblightclient.LightClientNative {
    *;
}

# Keep the JNI callback interface and its methods
# Native code calls "onStatusChange" by name, so it MUST NOT be renamed.
-keep interface com.nervosnetwork.ckblightclient.LightClientNative$StatusCallback {
    *;
}

# Ensure Kotlinx Serialization works correctly with R8
-keepattributes *Annotation*, InnerClasses
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.ref.Reference

# Keep the data classes used in API models just in case serialization needs them via reflection
-keep class com.rjnr.pocketnode.data.gateway.models.** { *; }

# Strip all Log calls in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Keep BouncyCastle (crypto provider)
-keep class org.bouncycastle.** { *; }

# Keep secp256k1-kmp (JNI-based crypto)
-keep class fr.acinq.secp256k1.** { *; }

# Ktor HTTP client
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Suppress warnings for classes not available on Android
-dontwarn javax.naming.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Keep Room database entities (annotations + field names used by Room codegen)
-keep class com.rjnr.pocketnode.data.database.entity.** { *; }

# Keep KeystoreEncryptionManager (Android Keystore + JCE crypto)
-keep class com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager { *; }
