# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep Compose runtime classes
#-keep class androidx.compose.** { *; }
#-keep class kotlin.** { *; }
#-keep class androidx.lifecycle.** { *; }
#-keep class androidx.activity.** { *; }
#-keep class com.getkeepsafe.relinker.** { *; }

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontobfuscate

# Keep native method names
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep all native classes
-keep class org.openmw.* { *; }
-keep class org.libsdl.app.** { *; }
# Plugins (separate dex files loaded at run time, e.g. nexus-autoslow.jar) call the plugin API and the Nexus download queue by name
-keep class org.openmw.plugins.** { *; }
-keep class org.openmw.modDownloader.NxmDownloads { *; }
-keep class com.hzy.lib7z.** { *; }
# 7-Zip-JBinding's native code finds these classes and their members by name (JNI_OnLoad aborts if one is gone)
-keep class net.sf.sevenzipjbinding.** { *; }
-keepclassmembers class net.sf.sevenzipjbinding.** { *; }
-keepclassmembers class org.libsdl.app.SDLActivity {
    public static <methods>;
}

-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn com.github.luben.zstd.ZstdInputStream
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.brotli.dec.BrotliInputStream
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier

# Swappy (frame pacing) looks its Java class up from native code
-keep class com.google.androidgamesdk.** { *; }

# ---- Size ----------------------------------------------------------------------------------------------------------
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively
# Debug and verbose logging is not wanted in a release (the custom on-screen log is separate)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
# Kotlin's null-check helpers only add a message to a crash that would happen anyway
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkParameterIsNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
}

# The texture pre-pass (libalpha3_texconv.so) calls TexturePrepass$Listener.onProgress by name
-keep interface org.openmw.utils.TexturePrepass$Listener { *; }
-keep class * implements org.openmw.utils.TexturePrepass$Listener { void onProgress(int, int, int, int, int, int); }
