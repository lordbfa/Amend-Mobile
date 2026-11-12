# libsecp256k1 JNI bindings
-keep class fr.acinq.secp256k1.** { *; }
-dontwarn fr.acinq.secp256k1.**
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Release builds keep nothing in the system log (logcat can be read over USB or by
# bug-report tools): every android.util.Log call is removed by R8.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static java.lang.String getStackTraceString(java.lang.Throwable);
}
