package com.github.catvod.crawler;

import android.content.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.Dns;

/** Public CatVod plugin ABI. Implementations arrive in the user's dex JAR. */
public class Spider {
    public void init(Context context) throws Exception { }
    public void init(Context context, String ext) throws Exception { init(context); }
    public String homeContent(boolean filter) throws Exception { return "{\"class\":[],\"list\":[]}"; }
    public String homeVideoContent() throws Exception { return "{\"list\":[]}"; }
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> ext) throws Exception { return "{\"list\":[]}"; }
    public String detailContent(List<String> ids) throws Exception { return "{\"list\":[]}"; }
    public String searchContent(String key, boolean quick) throws Exception { return "{\"list\":[]}"; }
    public String searchContent(String key, boolean quick, String pg) throws Exception { return searchContent(key, quick); }
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception { return "{\"parse\":0}"; }
    public boolean manualVideoCheck() throws Exception { return false; }
    public boolean isVideoFormat(String url) throws Exception { return false; }
    public Object[] proxyLocal(Map<String, String> params) throws Exception { return null; }
    public void cancelByTag() { }
    public void destroy() { }
    public String liveContent(String url) { return ""; }
    public static Dns safeDns() { return Dns.SYSTEM; }
}
