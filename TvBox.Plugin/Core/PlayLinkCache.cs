using System;
using System.Collections.Concurrent;

namespace TvBox.Plugin.Core
{
    /// <summary>
    /// 播放直链缓存：把「私有分集 id → 真实播放地址」的解析结果存起来。
    ///
    /// 为什么要它：每次播放都要 /TvBox/Play 现打 hub 的 playerContent，
    /// 首播要多等几秒，源站请求数也被放大（源站限流就开播失败）。
    /// 同步阶段的逐集校验本来就会走一遍解析，顺手塞进缓存，
    /// 之后同一集重播、换客户端、拖动重连都能直接命中。
    ///
    /// 失效策略：TTL（默认 25 分钟，配置 PlayCacheMinutes，0 = 关闭）+
    /// 配置变更/同步开始时整体清空。地址带时效签名的源即使 TTL 内失效，
    /// 播放失败后会自动重解析（缓存未命中即重解析，不会卡死）。
    /// </summary>
    public static class PlayLinkCache
    {
        public sealed class Entry
        {
            public string Url;
            public string Parse;
            public long ExpireAtTicks;
        }

        static readonly ConcurrentDictionary<string, Entry> _map =
            new ConcurrentDictionary<string, Entry>(StringComparer.Ordinal);

        /// <summary>TTL 分钟数，&lt;=0 时读写的都不生效（等于关闭缓存）。</summary>
        public static volatile int TtlMinutes = 25;

        static int _hits, _misses;
        public static int Hits => _hits;
        public static int Misses => _misses;
        public static int Count => _map.Count;

        public static string Key(string site, string flag, string id)
            => (site ?? "").Trim() + "|" + (flag ?? "").Trim() + "|" + (id ?? "").Trim();

        public static bool TryGet(string key, out Entry entry)
        {
            entry = null;
            if (TtlMinutes <= 0 || string.IsNullOrEmpty(key)) { _misses++; return false; }
            if (!_map.TryGetValue(key, out var e) || e == null) { _misses++; return false; }
            if (e.ExpireAtTicks <= DateTime.UtcNow.Ticks) { _map.TryRemove(key, out _); _misses++; return false; }
            _hits++;
            entry = e;
            return true;
        }

        public static void Set(string key, string url, string parse = null)
        {
            if (TtlMinutes <= 0 || string.IsNullOrEmpty(key) || string.IsNullOrWhiteSpace(url)) return;
            var e = new Entry
            {
                Url = url.Trim(),
                Parse = parse,
                ExpireAtTicks = DateTime.UtcNow.AddMinutes(Math.Min(TtlMinutes, 720)).Ticks,
            };
            // 顺手清过期项，避免无限增长（缓存量级 = 条目数，几千级，扫一遍很便宜）
            if (_map.Count > 20000)
            {
                var now = DateTime.UtcNow.Ticks;
                foreach (var kv in _map) if (kv.Value.ExpireAtTicks <= now) _map.TryRemove(kv.Key, out _);
            }
            _map[key] = e;
        }

        public static void Clear()
        {
            _map.Clear();
            _hits = 0;
            _misses = 0;
        }

        public static void ResetStats() { _hits = 0; _misses = 0; }
    }
}
