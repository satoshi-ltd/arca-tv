-dontobfuscate
-keepattributes SourceFile,LineNumberTable
-dontwarn androidx.annotation.**
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
