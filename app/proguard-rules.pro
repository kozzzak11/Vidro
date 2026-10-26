-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class app.vidro.browser.VidroJs { *; }
-keep class androidx.media3.** { *; }
-dontwarn javax.annotation.**
