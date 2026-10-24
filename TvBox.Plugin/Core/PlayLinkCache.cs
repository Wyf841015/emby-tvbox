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
                ExpireAtTicks = DateTime.UtcNow.AddSeconds(EffectiveTtlSeconds(url)).Ticks,
            };
            // 顺手清过期项，避免无限增长（缓存量级 = 条目数，几千级，扫一遍很便宜）
            if (_map.Count > 20000)
            {
                var now = DateTime.UtcNow.Ticks;
                foreach (var kv in _map) if (kv.Value.ExpireAtTicks <= now) _map.TryRemove(kv.Key, out _);
            }
            _map[key] = e;
        }

        /// <summary>把指定条目立刻作废（缓存命中但探测失败时用）。</summary>
        public static void Invalidate(string key)
        {
            if (!string.IsNullOrEmpty(key)) _map.TryRemove(key, out _);
        }

        /// <summary>
        /// 计算这条直链的有效缓存秒数：配置 TTL 与「URL 签名里的真实过期时间 − 2 分钟提前量」取小。
        /// 学 qmediasync 的做法（115 缓存 50 分钟 = 有效期 60 分钟留 10 分钟余量）：
        /// 固定 TTL 的坑是「缓存还没到 25 分钟、源站的 auth_key 先到期」，开播直接失败。
        /// </summary>
        static long EffectiveTtlSeconds(string url)
        {
            var cap = Math.Min(TtlMinutes, 720) * 60L;
            var real = SecondsUntilExpiry(url);
            if (!real.HasValue) return cap;
            var eff = (long)real.Value - 120;                 // 留 2 分钟提前量
            if (eff < 15) eff = Math.Min(Math.Max((long)real.Value, 0), 15);   // 快过期的也短存，别负数
            return Math.Max(0, Math.Min(cap, eff));
        }

        /// <summary>
        /// 从直链 URL 的签名参数里解析真实过期时间，返回距过期还有多少秒；解析不出返回 null。
        /// 覆盖常见形态：
        ///   auth_key=起始-结束-…（阿里云系）      keyTime=起始-结束（腾讯系）
        ///   expires=epoch / deadline=epoch（秒/毫秒自适应）
        ///   X-Amz-Date=YYYYMMDDTHHMMSSZ + X-Amz-Expires=秒（S3 预签名）
        /// </summary>
        public static int? SecondsUntilExpiry(string url)
        {
            if (string.IsNullOrWhiteSpace(url)) return null;
            const System.Text.RegularExpressions.RegexOptions O = System.Text.RegularExpressions.RegexOptions.IgnoreCase;
            long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            try
            {
                foreach (var k in new[] { "auth_key", "keyTime" })
                {
                    var m = System.Text.RegularExpressions.Regex.Match(url, k + @"=(\d{10,13})-(\d{10,13})", O);
                    if (!m.Success) continue;
                    long s = long.Parse(m.Groups[1].Value), e = long.Parse(m.Groups[2].Value);
                    if (s > 1_000_000_000_000L) { s /= 1000; e /= 1000; }
                    if (e > now) return (int)Math.Min(int.MaxValue, e - now);
                    return 0;
                }
                var me = System.Text.RegularExpressions.Regex.Match(url, @"[?&](?:expires|deadline)=(\d{10,13})", O);
                if (me.Success)
                {
                    long v = long.Parse(me.Groups[1].Value);
                    if (v > 1_000_000_000_000L) v /= 1000;
                    if (v > now) return (int)Math.Min(int.MaxValue, v - now);
                    return 0;
                }
                var amzE = System.Text.RegularExpressions.Regex.Match(url, @"X-Amz-Expires=(\d+)", O);
                var amzD = System.Text.RegularExpressions.Regex.Match(url, @"X-Amz-Date=(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z", O);
                if (amzE.Success && amzD.Success)
                {
                    var dt = new DateTime(int.Parse(amzD.Groups[1].Value), int.Parse(amzD.Groups[2].Value),
                                          int.Parse(amzD.Groups[3].Value), int.Parse(amzD.Groups[4].Value),
                                          int.Parse(amzD.Groups[5].Value), int.Parse(amzD.Groups[6].Value),
                                          DateTimeKind.Utc);
                    long end = new DateTimeOffset(dt, TimeSpan.Zero).ToUnixTimeSeconds()
                               + long.Parse(amzE.Groups[1].Value);
                    if (end > now) return (int)Math.Min(int.MaxValue, end - now);
                    return 0;
                }
            }
            catch { }
            return null;
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
