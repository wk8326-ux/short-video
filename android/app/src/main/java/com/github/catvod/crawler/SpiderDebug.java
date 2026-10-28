package com.github.catvod.crawler;

import android.util.Log;

/** Logging entry points used by independently built CatVod plugins. */
public final class SpiderDebug {
    public static void log(String text) { Log.d("TvBoxSpider", text == null ? "" : text); }
    public static void log(Object value) { log(String.valueOf(value)); }
    public static void log(Throwable error) { Log.e("TvBoxSpider", "Plugin failure", error); }
}
