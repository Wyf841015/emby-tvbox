using System;
using System.Collections.Generic;
using System.Linq;

namespace TvBox.Plugin.Core
{
    /// <summary>
    /// 解析苹果 CMS 的 vod_play_from / vod_play_url。
    ///
    /// 格式说明（这是整个对接里最容易踩坑的地方）：
    ///   vod_play_from = "量子$$$非凡"            —— 多条线路，用 $$$ 分隔
    ///   vod_play_url  = "第1集$http://a#第2集$http://b$$$第1集$http://c"
    ///                    └── 一条线路内，多集用 # 分隔，集名和地址用第一个 $ 分隔
    /// </summary>
    public static class PlayUrlParser
    {
        public static List<VodLine> Parse(string playFrom, string playUrl)
        {
            var lines = new List<VodLine>();
            if (string.IsNullOrWhiteSpace(playUrl)) return lines;

            var lineNames = string.IsNullOrWhiteSpace(playFrom)
                ? Array.Empty<string>()
                : playFrom.Split(new[] { "$$$" }, StringSplitOptions.None);

            var rawLines = playUrl.Split(new[] { "$$$" }, StringSplitOptions.None);
            for (int i = 0; i < rawLines.Length; i++)
            {
                var eps = ParseEpisodeList(rawLines[i]);
                if (eps.Count == 0) continue;
                lines.Add(new VodLine
                {
                    Name = i < lineNames.Length && !string.IsNullOrWhiteSpace(lineNames[i])
                        ? lineNames[i].Trim()
                        : "线路" + (i + 1),
                    Episodes = eps,
                });
            }
            return lines;
        }

        private static List<VodEpisode> ParseEpisodeList(string raw)
        {
            var list = new List<VodEpisode>();
            if (string.IsNullOrWhiteSpace(raw)) return list;

            var parts = raw.Split(new[] { '#' }, StringSplitOptions.RemoveEmptyEntries);
            int autoIndex = 0;
            foreach (var part in parts)
            {
                var seg = part.Trim();
                if (seg.Length == 0) continue;

                string name, url;
                var dollar = seg.IndexOf('$');
                if (dollar < 0)
                {
                    // 只有地址没有集名
                    name = null;
                    url = seg;
                }
                else
                {
                    name = seg.Substring(0, dollar).Trim();
                    url = seg.Substring(dollar + 1).Trim();
                }

                // 以前这里直接丢掉非 http 的地址，结果把「金牌影视/半日99/Sao火」这类
                // 把分集 id 当播放地址的源整站丢空。私有 id 留着，同步时经 hub
                // 的 /play/ 端点走 Spider.playerContent 换成真地址。
                if (string.IsNullOrWhiteSpace(url)) continue;
                // 只保留「http(s) 地址」或「私有分集 id」（形如 146660@1314815）：
                // magnet:/ed2k:/ftp: 这类留着也会被改写成 /TvBox/Play 变成死链。
                if (!IsPlayableUrl(url) && (LooksLikeCloudShare(url) || !LooksLikePrivateId(url))) continue;
                autoIndex++;
                list.Add(new VodEpisode
                {
                    Name = string.IsNullOrWhiteSpace(name) ? "第" + autoIndex + "集" : name,
                    Url = SanitizeUrl(url),   // 内嵌空格的解析接口地址在这里统一修好
                });
            }
            return list;
        }

        /// <summary>
        /// 清洗播放地址里非法字符。
        ///
        /// 实测大量源把 UA 直接拼进解析接口的 query 里且不编码：
        ///   https://api.xxx:7788/api/?key=…&amp;url=@|NONCE|token|Mozilla/5.0 (iPhone; CPU iPhone OS …)
        /// 里面有空格，ffmpeg 和多数客户端会直接判为非法 URL 拒播；
        /// .NET 的 Uri 会自己转义、但写进 strm 的还是带空格的原串，客户端照样播不了。
        /// 这里统一把空格换成 %20、控制字符转成 %XX，其余字符原样保留
        /// （| : @ / 这些是源站协议的一部分，不能动）。
        /// </summary>
        public static string SanitizeUrl(string url)
        {
            if (string.IsNullOrWhiteSpace(url)) return url;
            var u = url.Trim();
            var sb = new System.Text.StringBuilder(u.Length + 16);
            foreach (var ch in u)
            {
                if (ch == ' ') sb.Append("%20");
                else if (ch < 0x20 || ch == 0x7f)
                    sb.Append('%').Append(((int)ch).ToString("X2"));
                else sb.Append(ch);
            }
            return sb.ToString();
        }

        /// <summary>
        /// 判断地址是否真的能播。TVBox 的源里混着大量 magnet:、ed2k:、
        /// 网盘分享页、甚至空串，这些进了 Emby 只会变成一堆报错的条目。
        /// </summary>
        /// <summary>网盘分享页：这类即使能拿到地址也播不了，直接跳过。</summary>
        /// <summary>TVBox 的私有分集 id：形如 146660@1314815、98191、abc$def 之外的一般标识。</summary>
        public static bool LooksLikePrivateId(string url)
        {
            if (string.IsNullOrWhiteSpace(url)) return false;
            var s2 = url.Trim();
            if (s2.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                s2.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) return false;
            if (s2.Contains("://")) return false;            // magnet:/ed2k:/ftp:/thunder: 一律不算
            if (s2.Contains(" ")) return false;
            // 至少含一个数字，且只由 id 合法字符组成
            bool hasDigit = false;
            foreach (var c in s2)
            {
                if (char.IsDigit(c)) { hasDigit = true; continue; }
                if (!(char.IsLetterOrDigit(c) || c == '@' || c == '_' || c == '-' || c == '.')) return false;
            }
            return hasDigit;
        }

        public static bool LooksLikeCloudShare(string url)
        {
            if (string.IsNullOrWhiteSpace(url)) return false;
            var u = url.Trim();
            return u.Contains("pan.baidu.com") || u.Contains("aliyundrive.com")
                || u.Contains("alipan.com") || u.Contains("115.com") || u.Contains("夸克");
        }

        public static bool IsPlayableUrl(string url)
        {
            if (string.IsNullOrWhiteSpace(url)) return false;
            var u = url.Trim();
            if (!u.StartsWith("http://", StringComparison.OrdinalIgnoreCase) &&
                !u.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                return false;
            // 明显不是媒体的（网盘分享页、解析页）也排除
            if (u.Contains("pan.baidu.com") || u.Contains("aliyundrive.com") ||
                u.Contains("alipan.com") || u.Contains("115.com") || u.Contains("夸克"))
                return false;
            return true;
        }

        /// <summary>
        /// 在多条线路里挑一条最适合 Emby 的：
        /// 优先 m3u8（Emby 能 direct play），其次 mp4，最后任意 http；
        /// 同等条件下选集数最多的那条。
        /// </summary>
        public static VodLine PickBestLine(VodItem item)
        {
            if (item?.Lines == null || item.Lines.Count == 0) return null;
            return item.Lines
                .OrderByDescending(l => ScoreLine(l))
                .ThenByDescending(l => l.Episodes.Count)
                .FirstOrDefault();
        }

        private static int ScoreLine(VodLine line)
        {
            if (line?.Episodes == null || line.Episodes.Count == 0) return -1000;
            var first = line.Episodes[0].Url ?? string.Empty;
            int score = 0;
            if (first.Contains(".m3u8", StringComparison.OrdinalIgnoreCase)) score += 100;
            else if (first.Contains(".mp4", StringComparison.OrdinalIgnoreCase)) score += 60;
            else score += 20;

            // 全 http 的线路更靠谱
            if (line.Episodes.All(e => e.Url.StartsWith("https://", StringComparison.OrdinalIgnoreCase))) score += 10;
            return score;
        }
    }
}
