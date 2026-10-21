using System;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace TvBox.Plugin.Core
{
    /// <summary>
    /// 把「不能直接播」的播放地址解成真媒体地址。
    ///
    /// 源站返回的播放地址大致有三类真实形态（47 站抽样统计）：
    ///   1. 直接媒体     https://cdn/xxx/index.m3u8      —— 不用管
    ///   2. 解析接口     https://api.xxx/?key=…&amp;url=@|加密串|token|UA
    ///      这一类要先请求接口，接口返回 JSON 里才有真地址；
    ///   3. 网页播放页   https://www.xxx.com/v_play/bXZf….html
    ///      页面里通常有 m3u8 地址（或先 302 到真地址）。
    /// 不处理 2、3 就只能整条跳过，用户看到的是「这个源没内容」。
    ///
    /// 只在"校验阶段发现不是媒体"时调用，所以不会拖慢正常条目。
    /// </summary>
    public static class PlayUrlResolver
    {
        // 同时兼容裸地址与 JSON 里转义过的 https:\/\/host\/x.m3u8（Unescape 里还原）
        private static readonly Regex ReMedia = new Regex(
            @"https?:[^\s""'<>()\\]*?\.(?:m3u8|mp4|flv|ts|mkv)(?:\?[^\s""'<>()\\]*)?",
            RegexOptions.IgnoreCase | RegexOptions.Compiled);

        private static readonly string[] MediaExt = { ".m3u8", ".mp4", ".flv", ".ts", ".mkv" };

        /// <summary>地址本身就是媒体流（不是网页/接口）。</summary>
        public static bool LooksLikeDirectMedia(string url)
        {
            if (string.IsNullOrWhiteSpace(url)) return false;
            var path = url.Split('?')[0].ToLowerInvariant();
            foreach (var ext in MediaExt)
                if (path.EndsWith(ext)) return true;
            return false;
        }

        /// <summary>
        /// 尝试解出真媒体地址；解不出来返回 null（调用方照旧跳过这一集）。
        /// </summary>
        public static async Task<string> ResolveAsync(HttpClient http, string url, string referer, CancellationToken ct)
        {
            if (http == null || string.IsNullOrWhiteSpace(url)) return null;
            if (!url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) &&
                !url.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) return null;
            if (LooksLikeDirectMedia(url)) return url;

            try
            {
                using var req = new HttpRequestMessage(HttpMethod.Get, url);
                if (!string.IsNullOrWhiteSpace(referer) && Uri.TryCreate(referer, UriKind.Absolute, out var rf))
                    req.Headers.Referrer = rf;
                // 只读前 256KB：解析接口的 JSON 和播放页的脚本都在这个量级内
                req.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(0, 262143);

                using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct)
                                           .ConfigureAwait(false);
                if ((int)resp.StatusCode >= 400) return null;

                // 302 到真地址的情况：跟随后的地址本身就是媒体
                var final = resp.RequestMessage?.RequestUri?.ToString();
                if (!string.IsNullOrEmpty(final) &&
                    !string.Equals(final, url, StringComparison.Ordinal) &&
                    LooksLikeDirectMedia(final)) return final;

                var body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
                if (string.IsNullOrEmpty(body)) return null;

                // 有些源直接返回播放列表但没有 .m3u8 扩展名
                if (body.Contains("#EXTM3U")) return url;

                var m = ReMedia.Match(body);
                if (!m.Success) return null;

                var found = m.Value.Replace("\\/", "/");
                return found.StartsWith("http", StringComparison.OrdinalIgnoreCase) ? found : null;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch { return null; }
        }
    }
}
