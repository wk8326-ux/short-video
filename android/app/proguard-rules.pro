-keep class org.json.** { *; }
-keep class com.github.catvod.crawler.** { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keep class org.jsoup.** { *; }
-keep class com.google.gson.** { *; }
# Downloaded dex plugins reference these host-library names directly.
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
