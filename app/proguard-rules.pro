# Release builds are not minified. These rules keep the JS bridge and Media3
# entry points intact if shrinking is turned on later.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class androidx.media3.** { *; }
-dontwarn javax.annotation.**
