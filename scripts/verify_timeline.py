#!/usr/bin/env python3
"""
IPTV 时间轴算法离线验证 + 节目单快照生成。

与 Android 端 Channel.java 使用完全相同的算法：
  位置 = (当前毫秒 mod 该剧总毫秒) -> 累加时间轴二分定位

用法:
  python3 verify_timeline.py <durations.json> [输出目录]
"""
import json
import os
import sys
import time
import urllib.parse
from collections import OrderedDict
from datetime import datetime, timezone, timedelta

MAX_EP_MS = 3 * 3600 * 1000
MIN_EP_MS = 5 * 1000


def natural_key(name: str):
    """把文件名里的数字按数值比较: '02.mp4' < '10.mp4'"""
    import re
    parts = re.split(r'(\d+)', name)
    return [int(p) if p.isdigit() else p for p in parts]


def load(json_path):
    with open(json_path, encoding='utf-8') as f:
        raw = json.load(f)

    shows = OrderedDict()
    skipped = 0
    for url, dur in raw.items():
        if not isinstance(dur, (int, float)) or dur < MIN_EP_MS or dur > MAX_EP_MS:
            skipped += 1
            continue
        decoded = urllib.parse.unquote(url)
        seg = decoded.split('/')
        if len(seg) < 2:
            skipped += 1
            continue
        file_name, show = seg[-1], seg[-2]
        shows.setdefault(show, []).append(
            {'url': url, 'name': file_name, 'dur': int(dur)})

    channels = []
    for name, eps in shows.items():
        eps.sort(key=lambda e: natural_key(e['name']))
        acc = 0
        for e in eps:
            e['start'] = acc
            acc += e['dur']
        if acc > 0:
            channels.append({'name': name, 'eps': eps, 'total': acc})
    channels.sort(key=lambda c: natural_key(c['name']))
    return channels, len(raw), skipped


def position_at(ch, epoch_ms):
    """二分查找定位当前集"""
    t = epoch_ms % ch['total']
    lo, hi, ans = 0, len(ch['eps']) - 1, 0
    while lo <= hi:
        mid = (lo + hi) // 2
        if t < ch['eps'][mid]['start']:
            hi = mid - 1
        else:
            ans, lo = mid, mid + 1
    ep = ch['eps'][ans]
    return {
        'index': ans,
        'episode': ep,
        'offset_ms': t - ep['start'],
        'remain_ms': ep['dur'] - (t - ep['start']),
        'cycle_ms': ch['total'] - t,
    }


def clock(ms):
    t = max(0, int(ms)) // 1000
    h, m, s = t // 3600, (t % 3600) // 60, t % 60
    return f'{h:02d}:{m:02d}:{s:02d}' if h else f'{m:02d}:{s:02d}'


def human(ms):
    t = int(ms) // 1000
    d, h, m = t // 86400, (t % 86400) // 3600, (t % 3600) // 60
    out = ''
    if d:
        out += f'{d}天'
    if h:
        out += f'{h}小时'
    if m or not out:
        out += f'{m}分'
    return out


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else 'durations.json'
    outdir = sys.argv[2] if len(sys.argv) > 2 else '.'
    channels, raw, skipped = load(src)

    now_ms = int(time.time() * 1000)          # 自 1970-01-01 UTC 起的毫秒
    tz = timezone(timedelta(hours=8))
    now_str = datetime.fromtimestamp(now_ms / 1000, tz).strftime('%Y-%m-%d %H:%M:%S')

    print('=' * 78)
    print('IPTV 时间轴算法验证')
    print('=' * 78)
    print(f'原始条目      : {raw}')
    print(f'有效电视剧    : {len(channels)} 部')
    print(f'跳过的异常条目: {skipped}')
    print(f'总集数        : {sum(len(c["eps"]) for c in channels)}')
    print(f'当前时刻      : {now_str} (GMT+8)')
    print(f'纪元毫秒      : {now_ms}')
    print(f'纪元天数      : {now_ms / 86400000:.4f} 天')
    print('-' * 78)

    # 抽前 3 部 + 随机几部做详细展示
    sample = channels[:3]
    for c in sample:
        p = position_at(c, now_ms)
        print(f'\n【{c["name"]}】共 {len(c["eps"])} 集 / 整剧 {human(c["total"])}')
        print(f'  当前直播: 第 {p["index"] + 1} 集')
        print(f'  文件    : {p["episode"]["name"]}')
        print(f'  本集进度: {clock(p["offset_ms"])} / {clock(p["episode"]["dur"])}'
              f'  剩余 {clock(p["remain_ms"])}')
        print(f'  整剧轮回: {human(p["cycle_ms"])} 后从头重播')

    # 单调性验证：同一部剧在 0 / 1h / 24h / 1970 起点 应各有确定位置
    print('\n' + '-' * 78)
    print('边界与单调性验证（以《%s》为例）' % sample[0]['name'])
    ch = sample[0]
    for label, ms in [
        ('1970-01-01 00:00:00 UTC', 0),
        ('1970-01-01 00:00:01 UTC', 1000),
        ('1970-01-02 00:00:00 UTC', 86400_000),
        ('2000-01-01 00:00:00 UTC', 946_684_800_000),
        ('当前时刻', now_ms),
    ]:
        p = position_at(ch, ms)
        print(f'  {label:<26} -> 第{str(p["index"] + 1).rjust(3)}集  '
              f'{clock(p["offset_ms"])} / {clock(p["episode"]["dur"])}')

    # 全库快照
    snapshot = {
        'generatedAt': now_str,
        'epochMs': now_ms,
        'channelCount': len(channels),
        'episodeCount': sum(len(c['eps']) for c in channels),
        'channels': [],
    }
    for c in channels:
        p = position_at(c, now_ms)
        snapshot['channels'].append({
            'name': c['name'],
            'episodeCount': len(c['eps']),
            'totalMs': c['total'],
            'totalHuman': human(c['total']),
            'currentIndex': p['index'] + 1,
            'currentFile': p['episode']['name'],
            'offsetMs': p['offset_ms'],
            'offsetClock': clock(p['offset_ms']),
            'episodeDurationMs': p['episode']['dur'],
            'remainMs': p['remain_ms'],
            'url': urllib.parse.unquote(p['episode']['url']),
        })

    dst = os.path.join(outdir, 'schedule_snapshot.json')
    with open(dst, 'w', encoding='utf-8') as f:
        json.dump(snapshot, f, ensure_ascii=False, indent=2)
    print(f'\n快照已写入: {dst}')

    return 0


if __name__ == '__main__':
    sys.exit(main())
