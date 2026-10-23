using System;
using System.Collections.Concurrent;
using System.Net.Http;
using System.Text.Json;

namespace TvBox.Plugin.Core
{
    /// <summary>TMDB 命中结果。</summary>
    public struct TmdbHit
    {
        public string Id;
        public string Title;
        public string Year;
    }

    /// <summary>
    /// TMDB 规范化（可选功能，配置里填了 TmdbApiKey 才生效）。
    ///
    /// 只影响 **nfo 元数据**，不改目录名：源站标题常带「丨蓝光秒播」这类装饰，
    /// 直接拿来当目录名会刮不到；而 Emby 是「目录名 + nfo」两边都看，
    /// 只要 nfo 里有规范名/年份/tmdbid，就算目录名脏也能正确匹配。
    /// 改目录名反而会让历史文件变孤儿，得不偿失。
    ///
    /// 成本控制：结果全量缓存；连续失败 5 次就本轮放弃（API 挂了不能拖垮同步）。
    /// </summary>
    public static class TmdbClient
    {
        static readonly ConcurrentDictionary<string, TmdbHit?> _cache =
            new ConcurrentDictionary<string, TmdbHit?>(StringComparer.Ordinal);
        static readonly HttpClient _http = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
        static int _fails;
        public const int MaxFails = 5;
        public static int CacheCount => _cache.Count;
        public static void Clear() { _cache.Clear(); _fails = 0; }

        public static TmdbHit? Search(string apiKey, string lang, string query, string year, bool isSeries,
                                      Action<string> log = null)
        {
            if (string.IsNullOrWhiteSpace(apiKey) || string.IsNullOrWhiteSpace(query)) return null;
            if (_fails >= MaxFails) return null;
            var key = (isSeries ? "tv" : "movie") + "|" + (lang ?? "") + "|" + query.Trim() + "|" + (year ?? string.Empty);
            if (_cache.TryGetValue(key, out var cached)) return cached;

            TmdbHit? hit = null;
            try
            {
                var type = isSeries ? "tv" : "movie";
                var url = "https://api.themoviedb.org/3/search/" + type
                        + "?api_key=" + Uri.EscapeDataString(apiKey.Trim())
                        + "&language=" + Uri.EscapeDataString(string.IsNullOrWhiteSpace(lang) ? "zh-CN" : lang)
                        + "&query=" + Uri.EscapeDataString(query.Trim())
                        + (string.IsNullOrWhiteSpace(year) ? "" : "&year=" + Uri.EscapeDataString(year.Trim()));
                var body = _http.GetStringAsync(url).ConfigureAwait(false).GetAwaiter().GetResult();
                using (var doc = JsonDocument.Parse(body))
                {
                    if (doc.RootElement.TryGetProperty("results", out var arr) && arr.GetArrayLength() > 0)
                    {
                        var first = arr[0];
                        string id = first.TryGetProperty("id", out var idEl) ? idEl.ToString() : null;
                        string title = null;
                        var tKey = isSeries ? "name" : "title";
                        if (first.TryGetProperty(tKey, out var tEl)) title = tEl.GetString();
                        if (string.IsNullOrWhiteSpace(title) && first.TryGetProperty("original_title", out var ot))
                            title = ot.GetString();
                        if (string.IsNullOrWhiteSpace(title) && first.TryGetProperty("original_name", out var on))
                            title = on.GetString();
                        var dKey = isSeries ? "first_air_date" : "release_date";
                        string date = first.TryGetProperty(dKey, out var dEl) ? dEl.GetString() : null;
                        var y = (!string.IsNullOrEmpty(date) && date.Length >= 4) ? date.Substring(0, 4) : null;
                        if (!string.IsNullOrWhiteSpace(id))
                            hit = new TmdbHit { Id = id, Title = title, Year = y };
                    }
                }
                _fails = 0;
            }
            catch (Exception ex)
            {
                _fails++;
                log?.Invoke("[tmdb] 查询失败（第 " + _fails + " 次）" + query + ": " + ex.Message
                            + (_fails >= MaxFails ? "，本轮不再调用 TMDB" : ""));
            }
            _cache[key] = hit;
            return hit;
        }
    }
}
