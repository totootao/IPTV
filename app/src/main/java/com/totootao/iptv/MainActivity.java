package com.totootao.iptv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;

/**
 * 电视直播主界面。
 * 左侧电视剧列表 + 右侧播放区，播放位置由 1970 纪元时间推算。
 */
public class MainActivity extends Activity {

    private static final long TICK_MS = 1000L;

    private ExoPlayer player;
    private androidx.media3.ui.PlayerView playerView;
    private RecyclerView listView;
    private ChannelAdapter adapter;
    private TextView tvNowPlaying, tvEpisode, tvClock, tvProgress, tvStatus, tvSchedule;
    private View loading, headerProgress;
    private TextView loadingText;
    private int failStreak = 0;
    private View guidePanel, playerContainer;
    private View infoTop, infoBottom;
    private boolean guideActive = true;
    private boolean uiVisible = true;
    private static final long UI_TIMEOUT = 4500L;
    private final Runnable hideUi = new Runnable() {
        @Override
        public void run() {
            hideUi();
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Channel.Library library;
    private Channel current;
    private Channel.Position pendingPos;
    private boolean prepared = false;

    private ChannelRepository repo;

    /** 复用的 HTTP 客户端（用于播放与首字节嗅探容器类型） */
    private okhttp3.OkHttpClient httpClient;
    /** URL -> 真实容器 MIME 的嗅探缓存，避免每次切集重复请求 */
    private final java.util.Map<String, String> mimeCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** 切集序号令牌，丢弃过期的异步嗅探结果 */
    private long prepareSeq = 0;

    /** 每秒刷新：既要更新播放位置，也要在主界面刷新时钟 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (current != null) {
                Channel.Position p = Channel.positionAt(current, System.currentTimeMillis());
                bindLiveInfo(p);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        repo = new ChannelRepository(this);
        bindViews();
        buildPlayer();
        setupList();

        tvStatus.setText("正在获取节目单…");
        loading.setVisibility(View.VISIBLE);

        final String last = repo.getLastChannel();
        repo.load(new ChannelRepository.Callback() {
            @Override
            public void onSuccess(Channel.Library lib, boolean fromCache) {
                applyLibrary(lib, last, fromCache);
            }

            @Override
            public void onError(String message, Channel.Library cachedLib) {
                if (cachedLib != null) {
                    applyLibrary(cachedLib, last, true);
                    Toast.makeText(MainActivity.this,
                            "网络异常，使用本地缓存", Toast.LENGTH_SHORT).show();
                } else {
                    loading.setVisibility(View.GONE);
                    tvStatus.setText("节目单获取失败：" + message + "\n按「菜单」键重试");
                    adapter.setData(java.util.Collections.emptyList());
                }
            }
        });
    }

    private void bindViews() {
        listView = findViewById(R.id.channel_list);
        playerView = findViewById(R.id.player_view);
        playerContainer = findViewById(R.id.player_container);
        guidePanel = findViewById(R.id.guide_panel);
        infoTop = findViewById(R.id.info_top);
        infoBottom = findViewById(R.id.info_bottom);
        tvNowPlaying = findViewById(R.id.tv_now_playing);
        tvEpisode = findViewById(R.id.tv_episode);
        tvClock = findViewById(R.id.tv_clock);
        tvProgress = findViewById(R.id.tv_progress);
        tvStatus = findViewById(R.id.tv_status);
        tvSchedule = findViewById(R.id.tv_schedule);
        loading = findViewById(R.id.loading);
        loadingText = findViewById(R.id.loading_text);
        headerProgress = findViewById(R.id.header_progress);

        ImageButton btnReload = findViewById(R.id.btn_reload);
        btnReload.setOnClickListener(v -> reloadData());
        ImageButton btnInfo = findViewById(R.id.btn_info);
        btnInfo.setOnClickListener(v -> toggleSchedule());
    }

    private void buildPlayer() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(httpClient)
                .setUserAgent("IPTV/1.0 (Android TV)")
                .setDefaultRequestProperties(new java.util.HashMap<String, String>() {{
                    put("Accept", "*/*");
                }});
        DefaultDataSource.Factory ds = new DefaultDataSource.Factory(this, http);

        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(60000, 120000, 2500, 5000)
                .setBackBuffer(60000, true)
                .build();

        // TS 无索引：开启恒定码率 seek（免去先读文件尾部算时长），并加大 PTS 搜索窗口
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setTsExtractorTimestampSearchBytes(3_000_000);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(ds, extractors))
                .setLoadControl(load)
                .setSeekParameters(new SeekParameters(45_000_000L, 2_000_000L)) // 允许回退到 45s 内的前一关键帧，减少前向扫描
                .setHandleAudioBecomingNoisy(true)
                .build();
        playerView.setPlayer(player);
        playerView.setUseController(false);
        // 手机触摸习惯：轻点播放区切换控制栏（列表/信息条）显隐
        playerView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                toggleUiVisibility();
            }
            return true;
        });
        player.setVolume(1f);
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(ChannelRepository.TAG, "播放错误", error);
                failStreak++;
                loading.setVisibility(View.VISIBLE);
                // 出错不再静默转圈：把错误码亮出来，便于定位（如 IO 源 500、网络失败等）
                loadingText.setText("起流失败，正在重试…（错误码 " + error.errorCode + "）");
                // 单集播完/出错时，跳回该剧当前时间对应的位置（模拟直播不间断），
                // 连续失败则拉长重试间隔，避免高频死循环
                long delay = Math.min(2000L * failStreak, 10000L);
                handler.postDelayed(() -> {
                    if (current != null) syncToNow(current, true);
                }, delay);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    prepared = true;
                    failStreak = 0;
                    loadingText.setText("正在缓冲直播流…");
                    loading.setVisibility(View.GONE);
                    showUi();   // 起播后先露出信息，随后自动隐去进入沉浸模式
                }
            }
        });
    }

    private void setupList() {
        adapter = new ChannelAdapter();
        adapter.setOnClick(this::switchChannel);
        adapter.setFocusSink(() -> setGuideActive(true));
        listView.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL, false));
        listView.setAdapter(adapter);
        listView.setItemAnimator(null);
        listView.setFocusable(true);
        listView.requestFocus();
    }

    /**
     * 露出全部 UI（悬浮面板 + 上下信息条），并在无操作 UI_TIMEOUT 后自动隐去，
     * 让视频独占全屏——符合电视直播 App 的沉浸式习惯。
     */
    private void toggleUiVisibility() {
        if (uiVisible) {
            hideUi();
        } else {
            showUi();
        }
    }

    private void showUi() {
        uiVisible = true;
        handler.removeCallbacks(hideUi);
        guidePanel.setVisibility(View.VISIBLE);
        infoTop.setVisibility(View.VISIBLE);
        infoBottom.setVisibility(View.VISIBLE);
        applyGuideAppearance();
        infoTop.animate().alpha(1f).setDuration(160).start();
        infoBottom.animate().alpha(1f).setDuration(160).start();
        handler.postDelayed(hideUi, UI_TIMEOUT);
    }

    /** 进入沉浸模式：面板与信息条淡出隐藏 */
    private void hideUi() {
        if (!uiVisible) return;
        uiVisible = false;
        guidePanel.animate().alpha(0f).setDuration(280)
                .withEndAction(() -> guidePanel.setVisibility(View.INVISIBLE)).start();
        infoTop.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> infoTop.setVisibility(View.INVISIBLE)).start();
        infoBottom.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> infoBottom.setVisibility(View.INVISIBLE)).start();
    }

    /** 悬浮面板外观：聚焦时全亮，看视频时半透明 */
    private void applyGuideAppearance() {
        guidePanel.setAlpha(guideActive ? 1f : 0.4f);
    }

    /**
     * 切换画面比例：原比例(fit，不裁切有黑边) <-> 铺满(zoom，裁切填满)。
     * 默认按视频原比例播放。
     */
    private void toggleAspect() {
        if (playerView == null) return;
        if (playerView.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            Toast.makeText(this, "画面：铺满", Toast.LENGTH_SHORT).show();
        } else {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            Toast.makeText(this, "画面：原比例", Toast.LENGTH_SHORT).show();
        }
    }

    /** 列表项获焦 / 左右键切换时，调整面板亮起状态 */
    private void setGuideActive(boolean active) {
        guideActive = active;
        if (uiVisible) {
            applyGuideAppearance();
            handler.removeCallbacks(hideUi);
            handler.postDelayed(hideUi, UI_TIMEOUT);
        } else {
            showUi();
        }
    }

    private void applyLibrary(Channel.Library lib, String lastChannel, boolean fromCache) {
        library = lib;
        adapter.setData(lib.channels);
        tvStatus.setText(String.format(Locale.US,
                "共 %d 部剧 · %d 集%s",
                lib.channels.size(),
                countEpisodes(lib),
                fromCache ? " · 缓存" : ""));

        Channel target = null;
        if (!TextUtils.isEmpty(lastChannel)) {
            for (Channel c : lib.channels) {
                if (c.name.equals(lastChannel)) {
                    target = c;
                    break;
                }
            }
        }
        if (target == null && !lib.channels.isEmpty()) {
            target = lib.channels.get(0);   // 首次进入 -> 第一部
        }
        if (target != null) {
            switchChannel(target);
            adapter.scrollTo(target);
        }
    }

    private int countEpisodes(Channel.Library lib) {
        int n = 0;
        for (Channel c : lib.channels) n += c.episodes.size();
        return n;
    }

    /** 切换频道：按当前时刻推算位置并起播 */
    private void switchChannel(Channel target) {
        if (target == null || target == current && prepared) return;
        current = target;
        repo.setLastChannel(target.name);
        adapter.setActive(target);
        prepared = false;
        loading.setVisibility(View.VISIBLE);
        syncToNow(target, false);
    }

    /**
     * 定位到「现在」应有的位置。
     * restart=true 时强制重新 seek（用于错误恢复）。
     * 实际播放前会先读首字节嗅探真实容器类型，避免被 .ts 扩展名/服务器的
     * video/MP2T Content-Type 误导而用错 Extractor（已实测 AList 上大量「.ts」实为 MP4）。
     */
    private void syncToNow(Channel ch, boolean restart) {
        Channel.Position pos = Channel.positionAt(ch, System.currentTimeMillis());
        pendingPos = pos;
        bindLiveInfo(pos);
        prepareWithSniff(ch, pos.episode, pos.offsetMs);
    }

    /** 先嗅探真实容器 MIME，再在主线程真正挂载播放（带切集令牌防止竞态） */
    private void prepareWithSniff(Channel ch, Channel.Episode ep, long offsetMs) {
        String cached = mimeCache.get(ep.url);
        if (cached != null) {
            doPrepare(ch, ep, offsetMs, cached.isEmpty() ? null : cached);
            return;
        }
        prepared = false;
        loading.setVisibility(View.VISIBLE);
        final long mySeq = ++prepareSeq;
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(ep.url)
                .header("Range", "bytes=0-375")   // 取前 376 字节足矣判断容器
                .build();
        httpClient.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                mimeCache.put(ep.url, "");
                if (mySeq == prepareSeq) runOnUiThread(() -> doPrepare(ch, ep, offsetMs, null));
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response resp) {
                String mime = null;
                try {
                    byte[] head = resp.body() != null ? resp.body().bytes() : null;
                    if (head != null && head.length > 0) mime = detectMimeFromHead(head);
                } catch (Exception ignore) {
                    // 嗅探失败则回退交给 ExoPlayer 自行处理
                } finally {
                    resp.close();
                }
                mimeCache.put(ep.url, mime == null ? "" : mime);
                final String m = mime;
                if (mySeq == prepareSeq) runOnUiThread(() -> doPrepare(ch, ep, offsetMs, m));
            }
        });
    }

    /** 根据首字节魔数判断真实容器类型（不信任扩展名） */
    private String detectMimeFromHead(byte[] h) {
        if (h.length >= 12) {
            // ISO BMFF / MP4 : box size + 'ftyp'
            if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return MimeTypes.VIDEO_MP4;
            // EBML : Matroska / WebM
            if (h[0] == (byte) 0x1a && h[1] == (byte) 0x45 && h[2] == (byte) 0xdf && h[3] == (byte) 0xa3)
                return MimeTypes.VIDEO_MATROSKA;
        }
        if (h.length >= 3 && h[0] == 'F' && h[1] == 'L' && h[2] == 'V') return MimeTypes.VIDEO_FLV;
        // MPEG-TS : 同步字节 0x47 且按 188 字节对齐
        if (h.length >= 4 && h[0] == (byte) 0x47) {
            boolean ts = true;
            for (int off = 188; off < h.length; off += 188) {
                if (h[off] != (byte) 0x47) { ts = false; break; }
            }
            if (ts) return MimeTypes.VIDEO_MP2T;
        }
        return null;
    }

    /** 主线程：用正确的 MIME 挂载并播放 */
    private void doPrepare(Channel ch, Channel.Episode ep, long offsetMs, String mime) {
        current = ch;
        repo.setLastChannel(ch.name);
        adapter.setActive(ch);
        prepared = false;
        loading.setVisibility(View.VISIBLE);
        Channel.Position p = new Channel.Position(ch, ch.episodes.indexOf(ep), ep,
                offsetMs, ep.durationMs - offsetMs, 0, System.currentTimeMillis());
        pendingPos = p;
        bindLiveInfo(p);
        MediaItem.Builder mb = new MediaItem.Builder().setUri(ep.url);
        if (mime != null) mb.setMimeType(mime);
        player.setMediaItem(mb.build(), offsetMs);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    /** 刷新右下角/底部的直播信息 */
    private void bindLiveInfo(Channel.Position p) {
        if (p == null) return;

        tvNowPlaying.setText(p.channel.name);
        tvEpisode.setText(String.format(Locale.US, "第 %d 集 / 共 %d 集  ·  %s",
                p.index + 1, p.channel.episodes.size(), prettify(p.episode.name)));

        tvClock.setText(Channel.TimeUtil.dateTime(p.epochMs));

        long epMs = p.episode.durationMs;
        long el = p.offsetMs;
        long rm = p.remainMs;

        tvProgress.setText(String.format(Locale.US,
                "本集 %s / %s   （剩余 %s）",
                Channel.TimeUtil.clock(el),
                Channel.TimeUtil.clock(epMs),
                Channel.TimeUtil.clock(rm)));

        // 进度条宽度表示本集进度
        headerProgress.setVisibility(View.VISIBLE);
        float frac = epMs <= 0 ? 0f : (float) el / (float) epMs;
        View parent = (View) headerProgress.getParent();
        if (parent != null && parent.getWidth() > 0) {
            android.view.ViewGroup.LayoutParams lp = headerProgress.getLayoutParams();
            lp.width = Math.max(2, (int) (parent.getWidth() * frac));
            headerProgress.setLayoutParams(lp);
        }

        // 当前时间在全剧周期中的位置
        tvSchedule.setText(String.format(Locale.US,
                "全剧周期 %s  ·  本集 %s 后切换  ·  整剧 %s 后重播",
                Channel.TimeUtil.humanDuration(p.channel.totalMs),
                Channel.TimeUtil.clock(p.remainMs),
                Channel.TimeUtil.humanDuration(p.cycleMs)));
    }

    /** 去掉文件名里的扩展名和画质后缀，显示更干净 */
    private String prettify(String fileName) {
        String s = fileName;
        int dot = s.lastIndexOf('.');
        if (dot > 0 && s.length() - dot <= 5) s = s.substring(0, dot);
        s = s.replace('_', ' ').trim();
        if (s.length() > 34) s = s.substring(0, 33) + "…";
        return s;
    }

    private void toggleSchedule() {
        tvSchedule.setVisibility(
                tvSchedule.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void reloadData() {
        loading.setVisibility(View.VISIBLE);
        tvStatus.setText("正在刷新节目单…");
        final String last = repo.getLastChannel();
        repo.load(new ChannelRepository.Callback() {
            @Override
            public void onSuccess(Channel.Library lib, boolean fromCache) {
                applyLibrary(lib, last, fromCache);
                Toast.makeText(MainActivity.this, "节目单已更新", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message, Channel.Library cachedLib) {
                loading.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "刷新失败：" + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            reloadData();
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // 左：唤出悬浮频道列表并聚焦
                showUi();
                if (listView.findFocus() == null) listView.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 右：彻底进入沉浸看视频模式
                hideUi();
                playerContainer.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 任意导航键都先唤出 UI，再交给默认逻辑
                showUi();
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 沉浸（UI 已隐藏）时按 OK 切换 原比例 / 铺满；否则先唤出 UI
                if (!uiVisible) {
                    toggleAspect();
                    return true;
                }
                showUi();
                return super.onKeyDown(keyCode, event);
            default:
                showUi();
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        if (player != null) player.setPlayWhenReady(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(ticker);
        // 后台播放：不暂停 ExoPlayer，回到桌面后音频继续播，回前台自动续上画面
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
