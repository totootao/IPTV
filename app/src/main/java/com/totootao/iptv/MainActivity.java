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
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
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
    private RecyclerView listView;
    private ChannelAdapter adapter;
    private TextView tvNowPlaying, tvEpisode, tvClock, tvProgress, tvStatus, tvSchedule;
    private View loading, headerProgress;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Channel.Library library;
    private Channel current;
    private Channel.Position pendingPos;
    private boolean prepared = false;

    private ChannelRepository repo;

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
        tvNowPlaying = findViewById(R.id.tv_now_playing);
        tvEpisode = findViewById(R.id.tv_episode);
        tvClock = findViewById(R.id.tv_clock);
        tvProgress = findViewById(R.id.tv_progress);
        tvStatus = findViewById(R.id.tv_status);
        tvSchedule = findViewById(R.id.tv_schedule);
        loading = findViewById(R.id.loading);
        headerProgress = findViewById(R.id.header_progress);

        ImageButton btnReload = findViewById(R.id.btn_reload);
        btnReload.setOnClickListener(v -> reloadData());
        ImageButton btnInfo = findViewById(R.id.btn_info);
        btnInfo.setOnClickListener(v -> toggleSchedule());
    }

    private void buildPlayer() {
        OkHttpClient ok = new OkHttpClient.Builder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(ok)
                .setUserAgent("IPTV/1.0 (Android TV)")
                .setDefaultRequestProperties(new java.util.HashMap<String, String>() {{
                    put("Accept", "*/*");
                }});
        DefaultDataSource.Factory ds = new DefaultDataSource.Factory(this, http);

        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20000, 60000, 2500, 5000)
                .build();

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(ds))
                .setLoadControl(load)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.setVolume(1f);
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(ChannelRepository.TAG, "播放错误", error);
                // 单集播完/出错时，跳回该剧当前时间对应的位置（模拟直播不间断）
                handler.postDelayed(() -> {
                    if (current != null) syncToNow(current, true);
                }, 2000);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    prepared = true;
                    loading.setVisibility(View.GONE);
                }
            }
        });
    }

    private void setupList() {
        adapter = new ChannelAdapter();
        adapter.setOnClick(this::switchChannel);
        listView.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL, false));
        listView.setAdapter(adapter);
        listView.setItemAnimator(null);
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
     */
    private void syncToNow(Channel ch, boolean restart) {
        Channel.Position pos = Channel.positionAt(ch, System.currentTimeMillis());
        pendingPos = pos;
        bindLiveInfo(pos);

        MediaItem item = new MediaItem.Builder()
                .setUri(pos.episode.url)
                .setMimeType(guessMime(pos.episode.name))
                .build();

        if (restart || player.getMediaItemCount() == 0) {
            player.setMediaItem(item, pos.offsetMs);
            player.prepare();
            player.setPlayWhenReady(true);
        } else {
            player.setMediaItem(item, pos.offsetMs);
            player.prepare();
            player.setPlayWhenReady(true);
        }
    }

    private String guessMime(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".mkv")) return MimeTypes.VIDEO_MATROSKA;
        if (n.endsWith(".ts")) return MimeTypes.VIDEO_MP2T;
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return MimeTypes.VIDEO_MP4;
        if (n.endsWith(".webm")) return MimeTypes.VIDEO_WEBM;
        if (n.endsWith(".flv")) return MimeTypes.VIDEO_FLV;
        return null;
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
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            // 左键聚焦列表，便于遥控器操作
            listView.requestFocus();
        }
        return super.onKeyDown(keyCode, event);
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
        if (player != null) player.setPlayWhenReady(false);
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
