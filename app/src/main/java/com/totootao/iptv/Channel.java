package com.totootao.iptv;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 频道数据模型。
 *
 * 数据源 durations.json 的结构：
 *   { "https://.../视频/电视剧/三国演义/01桃园三结义.mkv": 2624448, ... }
 *   值 = 该视频时长（毫秒）
 *   路径倒数第二段 = 电视剧名字
 *
 * 时间轴算法（各剧独立轮回）：
 *   对每部剧，先把它的所有集按文件名顺序排列，累加得到整部剧的总时长 T（毫秒）。
 *   当前时刻从 1970-01-01 00:00:00 UTC 起算得到 E（毫秒）。
 *   该剧当前偏移 = E mod T。
 *   在累加时间轴上用二分查找定位到具体集，再算出该集内的秒数。
 *
 * 这样任意一部剧在任意时刻都有唯一确定的「正在直播」位置，
 * 且因为所有剧共用同一个 1970 起点，切换剧集时不会出现跳变。
 */
public class Channel {

    public static class Episode {
        public final String url;      // 原始（已编码）地址
        public final String name;     // 文件名
        public final long durationMs; // 时长
        public final long startMs;    // 在整部剧时间轴上的起始位置

        Episode(String url, String name, long durationMs, long startMs) {
            this.url = url;
            this.name = name;
            this.durationMs = durationMs;
            this.startMs = startMs;
        }
    }

    /** 单集时长上限保护：超过 3 小时视为异常数据，跳过以免污染整剧时间轴 */
    private static final long MAX_EPISODE_MS = 3 * 3600_000L;
    private static final long MIN_EPISODE_MS = 5_000L;

    public final String name;
    public final List<Episode> episodes;
    public final long totalMs;

    private Channel(String name, List<Episode> episodes, long totalMs) {
        this.name = name;
        this.episodes = episodes;
        this.totalMs = totalMs;
    }

    /** 解析结果 */
    public static class Library {
        public final List<Channel> channels;
        public final int rawEntries;
        public final int skipped;

        Library(List<Channel> channels, int rawEntries, int skipped) {
            this.channels = channels;
            this.rawEntries = rawEntries;
            this.skipped = skipped;
        }
    }

    /** 当前直播位置 */
    public static class Position {
        public final Channel channel;
        public final int index;        // 集下标
        public final Episode episode;
        public final long offsetMs;    // 本集内已播毫秒
        public final long remainMs;    // 本集剩余毫秒
        public final long cycleMs;     // 距下次轮回整圈的时间
        public final long epochMs;     // 计算所用的纪元毫秒

        Position(Channel channel, int index, Episode episode,
                 long offsetMs, long remainMs, long cycleMs, long epochMs) {
            this.channel = channel;
            this.index = index;
            this.episode = episode;
            this.offsetMs = offsetMs;
            this.remainMs = remainMs;
            this.cycleMs = cycleMs;
            this.epochMs = epochMs;
        }
    }

    /**
     * 从 durations.json 文本构建频道库。
     */
    public static Library parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);

        // 保持插入顺序，让剧集顺序与 JSON 中一致（即目录内的自然顺序）
        Map<String, List<Episode>> grouped = new LinkedHashMap<>();
        Map<String, Long> runningStart = new LinkedHashMap<>();

        int raw = 0;
        int skipped = 0;
        java.util.Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String url = keys.next();
            long dur = root.optLong(url, -1L);   // 毫秒
            raw++;

            if (dur < MIN_EPISODE_MS || dur > MAX_EPISODE_MS) {
                skipped++;
                continue;
            }

            String decoded;
            try {
                decoded = URLDecoder.decode(url, "UTF-8");
            } catch (Exception e) {
                decoded = url;
            }

            String[] seg = decoded.split("/");
            if (seg.length < 2) {
                skipped++;
                continue;
            }

            String fileName = seg[seg.length - 1];
            String showName = seg[seg.length - 2];   // 倒数第二列 = 剧名

            if (showName.isEmpty() || fileName.isEmpty()) {
                skipped++;
                continue;
            }

            List<Episode> list = grouped.get(showName);
            if (list == null) {
                list = new ArrayList<>();
                grouped.put(showName, list);
                runningStart.put(showName, 0L);
            }
            long start = runningStart.get(showName);
            list.add(new Episode(url, fileName, dur, start));
            runningStart.put(showName, start + dur);
        }

        List<Channel> out = new ArrayList<>();
        for (Map.Entry<String, List<Episode>> e : grouped.entrySet()) {
            List<Episode> eps = e.getValue();
            if (eps.isEmpty()) continue;

            // 按文件名做自然序排序，保证 01 < 02 < ... < 10
            Collections.sort(eps, (a, b) -> NaturalOrder.compare(a.name, b.name));

            // 排序后重建时间轴
            long acc = 0;
            List<Episode> fixed = new ArrayList<>(eps.size());
            for (Episode old : eps) {
                fixed.add(new Episode(old.url, old.name, old.durationMs, acc));
                acc += old.durationMs;
            }
            if (acc <= 0) continue;
            out.add(new Channel(e.getKey(), Collections.unmodifiableList(fixed), acc));
        }

        // 剧名排序，界面更整齐
        Collections.sort(out, (a, b) -> NaturalOrder.compare(a.name, b.name));

        return new Library(Collections.unmodifiableList(out), raw, skipped);
    }

    /** 计算某时刻的直播位置 */
    public static Position positionAt(Channel ch, long epochMs) {
        long cycle = Math.floorMod(epochMs, ch.totalMs);
        int idx = ch.indexOfTime(cycle);
        Episode ep = ch.episodes.get(idx);
        long offset = cycle - ep.startMs;
        long remain = ep.durationMs - offset;
        long next = ch.totalMs - cycle;
        return new Position(ch, idx, ep, offset, remain, next, epochMs);
    }

    /** 以累加时间轴二分查找所在的集 */
    private int indexOfTime(long t) {
        int lo = 0, hi = episodes.size() - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Episode ep = episodes.get(mid);
            if (t < ep.startMs) {
                hi = mid - 1;
            } else {
                ans = mid;
                lo = mid + 1;
            }
        }
        return ans;
    }

    /** 便于调试与 JSON 快照输出的紧凑序列 */
    public String summary() {
        return name + " | " + episodes.size() + "集 | 总时长 " + TimeUtil.humanDuration(totalMs);
    }

    /**
     * 自然序比较：把字符串里的数字按数值比较，
     * 例如 "02.mp4" < "10.mp4" 而非字典序的 "10.mp4" < "02.mp4"。
     */
    public static final class NaturalOrder {
        public static int compare(String a, String b) {
            int i = 0, j = 0, la = a.length(), lb = b.length();
            while (i < la && j < lb) {
                char ca = a.charAt(i), cb = b.charAt(j);
                if (Character.isDigit(ca) && Character.isDigit(cb)) {
                    int si = i, sj = j;
                    while (si < la && Character.isDigit(a.charAt(si))) si++;
                    while (sj < lb && Character.isDigit(b.charAt(sj))) sj++;
                    String na = trimZero(a.substring(i, si));
                    String nb = trimZero(b.substring(sj, sj == j ? sj : sj));
                    nb = trimZero(b.substring(j, sj));
                    int cmp;
                    if (na.length() != nb.length()) {
                        cmp = na.length() - nb.length();
                    } else {
                        cmp = na.compareTo(nb);
                    }
                    if (cmp != 0) return cmp;
                    i = si;
                    j = sj;
                } else {
                    if (ca != cb) {
                        // 数字优先于其它字符，保证 "2" < "EP2" 之类的稳定结果
                        return ca - cb;
                    }
                    i++;
                    j++;
                }
            }
            return (la - i) - (lb - j);
        }

        private static String trimZero(String s) {
            int k = 0;
            while (k < s.length() - 1 && s.charAt(k) == '0') k++;
            return s.substring(k);
        }
    }

    /** 时间格式化 */
    public static final class TimeUtil {
        /** 毫秒 -> "01:23:45" 或 "12:34" */
        public static String clock(long ms) {
            if (ms < 0) ms = 0;
            long total = ms / 1000;
            long h = total / 3600;
            long m = (total % 3600) / 60;
            long s = total % 60;
            if (h > 0) {
                return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s);
            }
            return String.format(java.util.Locale.US, "%02d:%02d", m, s);
        }

        /** 毫秒 -> "1天2小时3分" */
        public static String humanDuration(long ms) {
            long total = ms / 1000;
            long d = total / 86400;
            long h = (total % 86400) / 3600;
            long m = (total % 3600) / 60;
            StringBuilder sb = new StringBuilder();
            if (d > 0) sb.append(d).append("天");
            if (h > 0) sb.append(h).append("小时");
            if (m > 0 || sb.length() == 0) sb.append(m).append("分");
            return sb.toString();
        }

        /** 判断是否处于整点（用于时钟显示） */
        public static String dateTime(long epochMs) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(epochMs);
            return String.format(java.util.Locale.US, "%04d-%02d-%02d %02d:%02d:%02d",
                    c.get(java.util.Calendar.YEAR),
                    c.get(java.util.Calendar.MONTH) + 1,
                    c.get(java.util.Calendar.DAY_OF_MONTH),
                    c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE),
                    c.get(java.util.Calendar.SECOND));
        }
    }

    /** 供外部按名索引 */
    public static Map<String, Channel> indexByName(List<Channel> list) {
        Map<String, Channel> m = new TreeMap<>();
        for (Channel c : list) m.put(c.name, c);
        return m;
    }
}
