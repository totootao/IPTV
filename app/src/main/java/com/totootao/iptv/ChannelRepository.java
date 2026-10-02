package com.totootao.iptv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 负责下载 / 缓存 durations.json 并构建频道库。
 */
public class ChannelRepository {

    public static final String TAG = "IPTV";
    public static final String DATA_URL =
            "https://www.totootao.top/alist/d/3-90/mnt/nas/%E8%A7%86%E9%A2%91/durations.json";
    /** GitHub Releases 上的镜像，作为备用源（30 分钟级新鲜度） */
    public static final String FALLBACK_URL =
            "https://ghproxy.totootao.top/https://raw.githubusercontent.com/totootao/IPTV/gh-data/durations.json";

    private static final String PREF = "iptv_prefs";
    private static final String KEY_CACHE = "cache_json";
    private static final String KEY_CACHE_TIME = "cache_time";
    private static final String KEY_LAST_CHANNEL = "last_channel";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ChannelRepository(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public interface Callback {
        void onSuccess(Channel.Library lib, boolean fromCache);
        void onError(String message, Channel.Library cachedLib);
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 上次播放的电视剧名（首次进入返回 null） */
    public String getLastChannel() {
        String s = prefs().getString(KEY_LAST_CHANNEL, null);
        return TextUtils.isEmpty(s) ? null : s;
    }

    public void setLastChannel(String name) {
        prefs().edit().putString(KEY_LAST_CHANNEL, name).apply();
    }

    /** 读缓存（可能为 null） */
    public Channel.Library loadCached() {
        String json = prefs().getString(KEY_CACHE, null);
        if (TextUtils.isEmpty(json)) return null;
        try {
            return Channel.parse(json);
        } catch (Exception e) {
            Log.w(TAG, "缓存解析失败", e);
            return null;
        }
    }

    /**
     * 拉取数据：先给缓存（秒开），再从网络刷新。
     * 网络失败时如果本地有缓存，仍然算成功（fromCache=true）。
     */
    public void load(final Callback cb) {
        new Thread(() -> {
            final Channel.Library cached = loadCached();
            if (cached != null) {
                main.post(() -> cb.onSuccess(cached, true));
            }

            String json = null;
            String err = null;
            String[] sources = new String[]{DATA_URL, FALLBACK_URL};
            for (String src : sources) {
                try {
                    json = httpGet(src);
                    if (json != null && json.length() > 1000) {
                        Log.i(TAG, "拉取成功: " + src + " 长度=" + json.length());
                        break;
                    }
                    json = null;
                } catch (Exception e) {
                    err = e.getMessage();
                    Log.w(TAG, "拉取失败 " + src + " -> " + err);
                }
            }

            if (json == null) {
                final String fem = err == null ? "无法获取节目单数据" : err;
                main.post(() -> cb.onError(fem, cached));
                return;
            }

            final String good = json;
            try {
                final Channel.Library lib = Channel.parse(good);
                prefs().edit()
                        .putString(KEY_CACHE, good)
                        .putLong(KEY_CACHE_TIME, System.currentTimeMillis())
                        .apply();
                main.post(() -> cb.onSuccess(lib, false));
            } catch (Exception e) {
                final String fem = "节目单解析失败: " + e.getMessage();
                main.post(() -> cb.onError(fem, cached));
            }
        }, "channel-loader").start();
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android TV) IPTV/1.0");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("Accept", "application/json,*/*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                throw new Exception("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase().contains("gzip")) {
                in = new GZIPInputStream(in);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, "UTF-8"), 65536)) {
                char[] buf = new char[65536];
                int n;
                while ((n = br.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
            }
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 备用源地址列表，便于上层展示 */
    public static List<String> sources() {
        List<String> l = new ArrayList<>();
        l.add(DATA_URL);
        l.add(FALLBACK_URL);
        return l;
    }
}
