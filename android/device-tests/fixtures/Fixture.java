package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/** Built to dex by build_type3_fixture.py; never shipped inside the application. */
public class Fixture extends Spider {
    private String base;
    private String mode;
    @Override public void init(Context context, String ext) throws Exception {
        JSONObject config = new JSONObject(ext);
        base = config.getString("base"); mode = config.optString("mode");
    }
    private JSONObject movie() throws Exception {
        return new JSONObject().put("vod_id", "plugin-film").put("vod_name", "JAR plugin playback test")
            .put("vod_play_from", "fixture").put("vod_play_url", "Sample$opaque-token");
    }
    private String page() throws Exception {
        return new JSONObject().put("page",1).put("pagecount",1).put("total",1)
            .put("list",new JSONArray().put(movie())).toString();
    }
    @Override public String homeContent(boolean filter) throws Exception {
        return new JSONObject(page()).put("class",new JSONArray().put(new JSONObject()
            .put("type_id","films").put("type_name","JAR movies"))).toString();
    }
    @Override public String categoryContent(String tid,String pg,boolean filter,HashMap<String,String> ext) throws Exception { return page(); }
    @Override public String detailContent(List<String> ids) throws Exception { return page(); }
    @Override public String searchContent(String key,boolean quick,String pg) throws Exception { return page(); }
    @Override public String playerContent(String flag,String id,List<String> vipFlags) throws Exception {
        if (!id.equals("opaque-token")) throw new IllegalArgumentException("Remote token changed");
        return new JSONObject().put("parse",0).put("url",mode.equals("proxy")?"http://127.0.0.1:9978/proxy?do=playlist":base+"/protected/sample.m3u8")
            .put("header",new JSONObject().put("X-TVBox-Test","fixture")).toString();
    }
    @Override public Object[] proxyLocal(Map<String,String> params) throws Exception {
        if (!"playlist".equals(params.get("do"))) throw new IllegalArgumentException("Proxy query lost");
        HttpURLConnection connection = (HttpURLConnection)new URL(base+"/protected/sample.m3u8").openConnection();
        connection.setRequestProperty("X-TVBox-Test","fixture");
        String playlist;
        try(java.io.InputStream input=connection.getInputStream()) {
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
            byte[] bytes=new byte[8192];int read;
            while((read=input.read(bytes))!=-1)output.write(bytes,0,read);
            playlist=new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
        playlist=playlist.replaceAll("(?m)^(sample-\\d+\\.ts)$",base+"/protected/$1");
        return new Object[]{200,"application/vnd.apple.mpegurl",new ByteArrayInputStream(playlist.getBytes(java.nio.charset.StandardCharsets.UTF_8))};
    }
}
