using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace TvBox.Plugin.Core
{
    /// <summary>一次写入的结果。</summary>
    public struct WriteOutcome
    {
        public bool Created;
        public bool Updated;
        public string Path;
    }

    /// <summary>
    /// 把 TVBox 的点播条目写成 Emby 能直接扫的 .strm 文件树。
    ///
    /// 目录结构完全按 Emby 官方命名规范（这样后续 Emby 才能正确刮削剧集信息）：
    ///   &lt;root&gt;/movies/&lt;片名 (年份)&gt;/&lt;片名 (年份)&gt;.strm
    ///   &lt;root&gt;/tv/&lt;剧名 (年份)&gt;/Season 01/&lt;剧名 (年份)&gt; - S01E01 - &lt;集名&gt;.strm
    ///
    /// 关键点（少一条 Emby 就认不出来）：
    ///   · 片名/剧名后面带 (年份)，同名不同版才不会串
    ///   · 集文件必须是「剧名 - SxxEyy - 集名」，季目录必须是 Season NN
    ///   · 电影文件名与所在目录同名
    ///   · 不再插入「站点名」这一层 —— Emby 以「含 Season 目录的那层」当剧名，
    ///     中间多一层会让刮削器拿站点名去匹配；去掉后同一部剧多个源会自然合并
    ///     （先同步的站先占集号，后同集的覆盖 URL，等于多源冗余）。
    /// </summary>
    public class StrmWriter
    {
        private readonly string _root;
        private readonly Action<string> _log;

        /// <summary>把原始播放地址改写成实际写进 strm 的地址（用于挂代理）。</summary>
        public Func<string, string> UrlRewriter { get; set; }

        /// <summary>写 movie.nfo / tvshow.nfo（Emby 优先读本地元数据，刮削失败也有简介）。</summary>
        public bool WriteNfo { get; set; } = true;

        /// <summary>把源站封面下载成同目录 poster.jpg。</summary>
        public bool DownloadPoster { get; set; } = true;

        /// <summary>海报大小上限（字节），超过就跳过。</summary>
        public int PosterMaxBytes { get; set; } = 4 * 1024 * 1024;

        /// <summary>图片下载器（由 SyncService 注入：带超时、大小上限、UA）。</summary>
        public Func<string, byte[]> FetchImage { get; set; }

        /// <summary>单次同步最多删多少个 strm（防误删保险，0 = 不限）。</summary>
        public int MaxDeletePerRun { get; set; } = 300;

        /// <summary>本次因超上限而没删的数量（给同步汇总提示用）。</summary>
        public int DeletesSkipped { get; private set; }

        public StrmWriter(string root, Action<string> log = null)
        {
            _root = root;
            _log = log ?? (_ => { });
        }

        /// <summary>最近一次写失败的原因（给同步汇总用，避免「同步成功但没文件」）。</summary>
        public string LastError { get; private set; }

        public string MoviesRoot => Path.Combine(_root, "movies");
        public string TvRoot => Path.Combine(_root, "tv");

        public string Root => _root;

        /// <summary>
        /// 我们写过的文件清单。清理孤儿必须以此为准 —— 只删「清单里有、这次不要了」的，
        /// 否则 OutputRoot 指到用户已有媒体库时，用户自己的 .strm 会被当孤儿删掉。
        /// </summary>
        private string ManifestPath => Path.Combine(_root, ".tvbox-manifest.json");

        private HashSet<string> LoadManifest()
        {
            var set = new HashSet<string>(StringComparer.Ordinal);
            try
            {
                if (!File.Exists(ManifestPath)) return set;
                var txt = File.ReadAllText(ManifestPath);
                foreach (var p in System.Text.Json.JsonSerializer.Deserialize<List<string>>(txt) ?? new List<string>())
                    if (!string.IsNullOrWhiteSpace(p)) set.Add(p);
            }
            catch (Exception ex) { _log("读 manifest 失败: " + ex.Message); }
            return set;
        }

        /// <summary>把本次「应该存在」的文件集合写成清单（下次清理的判据）。</summary>
        public void SaveManifest(IEnumerable<string> keep)
        {
            try
            {
                var dir = Path.GetDirectoryName(ManifestPath);
                if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir)) Directory.CreateDirectory(dir);
                var list = keep == null ? new List<string>() : keep.ToList();
                File.WriteAllText(ManifestPath,
                    System.Text.Json.JsonSerializer.Serialize(list), new UTF8Encoding(false));
            }
            catch (Exception ex) { _log("写 manifest 失败: " + ex.Message); }
        }

        // ───────────────────── 写入 ─────────────────────

        /// <summary>
        /// 电影：&lt;root&gt;/movies/&lt;片名 (年份)&gt;/&lt;片名 (年份)&gt;.strm
        /// siteName 只用于日志，不再进路径（进了 Emby 就认不出片名）。
        /// </summary>
        public WriteOutcome WriteMovie(string siteName, VodItem item, VodLine line)
        {
            var ep = line?.Episodes?.FirstOrDefault();
            if (ep == null) return default;

            var folder = SafeName(TitleWithYear(item.Name, item.Year, item.Remarks));
            var dir = Path.Combine(MoviesRoot, folder);
            var file = Path.Combine(dir, folder + ".strm");
            var outcome = WriteStrmFile(file, ep.Url);
            if (!string.IsNullOrEmpty(outcome.Path))
            {
                EnsureNfo(dir, item, false);
                EnsurePoster(dir, item.Pic);
            }
            return outcome;
        }

        /// <summary>
        /// 剧集：&lt;root&gt;/tv/&lt;剧名 (年份)&gt;/Season NN/&lt;剧名 (年份)&gt; - SxxEyy - &lt;集名&gt;.strm
        ///
        /// 集号优先从集名里解析（第12集 / EP12 / S02E05 / 纯数字 12），解析不到才按顺序补。
        /// 集名能解析出季号就按季分目录，否则一律 Season 01。
        /// 同一季内集号保证不重复：源站常把「正片」「原片」和 2、3、4… 混在一个列表里，
        /// 直接按顺序编号会让正片占掉第 1 集、后面整体错位。
        /// </summary>
        public List<WriteOutcome> WriteSeries(string siteName, VodItem item, VodLine line)
        {
            var results = new List<WriteOutcome>();
            if (line?.Episodes == null || line.Episodes.Count == 0) return results;

            // 整条属于同一季时（备注「第二季」），集名不带季号也归到 S02，别全塞进 Season 01
            int baseSeason = SeasonFromText(item.Remarks);
            if (baseSeason <= 0) baseSeason = SeasonFromText(item.Name);
            if (baseSeason <= 0) baseSeason = 1;
            var baseTitle = SafeName(TitleWithYear(item.Name, item.Year, item.Remarks));
            var showDir = Path.Combine(TvRoot, baseTitle);
            var used = new Dictionary<int, HashSet<int>>();

            // 有真实分集时，跳过「正片 / 原片 / HD」这类整片别名：
            // 它们只是同一部片的多条线路，写进去会把第 1、2 集的号占掉，后面整体错位。
            var realCount = line.Episodes.Count(e => !IsPlaceholderEpisode(e.Name));

            for (int i = 0; i < line.Episodes.Count; i++)
            {
                var ep = line.Episodes[i];
                if (realCount > 1 && IsPlaceholderEpisode(ep.Name)) continue;
                var epTitle = CleanEpisodeTitle(ep.Name, out var season, out var num);
                if (season <= 0) season = baseSeason;
                if (num <= 0) num = i + 1;

                if (!used.TryGetValue(season, out var set))
                {
                    set = new HashSet<int>();
                    used[season] = set;
                }
                while (set.Contains(num)) num++;
                set.Add(num);

                var seasonDir = Path.Combine(showDir, "Season " + season.ToString("00"));
                var name = new StringBuilder(baseTitle)
                    .Append(" - S").Append(season.ToString("00"))
                    .Append("E").Append(num.ToString("00"));
                if (!string.IsNullOrEmpty(epTitle)) name.Append(" - ").Append(SafeName(epTitle));
                results.Add(WriteStrmFile(Path.Combine(seasonDir, name + ".strm"), ep.Url));
            }

            if (results.Any(r => !string.IsNullOrEmpty(r.Path)))
            {
                EnsureNfo(showDir, item, true);
                EnsurePoster(showDir, item.Pic);
            }
            return results;
        }

        // ───────────────────── 元数据（nfo / 海报）─────────────────────

        /// <summary>
        /// 写 nfo。Emby 只要在条目目录里看到 movie.nfo / tvshow.nfo，就优先用本地
        /// 元数据，不再依赖 TMDB 按中文名匹配 —— tvbox 源的名字常带「丨蓝光秒播」
        /// 这种装饰，刮削失败概率很高，nfo 是最稳的兜底。
        /// </summary>
        private void EnsureNfo(string dir, VodItem item, bool isSeries)
        {
            if (!WriteNfo || string.IsNullOrWhiteSpace(dir)) return;
            try
            {
                var title = CleanTitle(item.Name);
                var year = ExtractYear(item.Year) ?? YearFromTitle(item.Name);
                var sb = new StringBuilder();
                sb.Append("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n");
                sb.Append(isSeries ? "<tvshow>\n" : "<movie>\n");
                sb.Append("  <title>").Append(Xml(title)).Append("</title>\n");
                sb.Append("  <originaltitle>").Append(Xml(item.Name ?? title)).Append("</originaltitle>\n");
                if (!string.IsNullOrEmpty(year)) sb.Append("  <year>").Append(Xml(year)).Append("</year>\n");
                if (!string.IsNullOrWhiteSpace(item.TypeName))
                {
                    sb.Append("  <genre>").Append(Xml(item.TypeName)).Append("</genre>\n");
                    sb.Append("  <tag>").Append(Xml(item.TypeName)).Append("</tag>\n");
                }
                if (!string.IsNullOrWhiteSpace(item.Area))
                    sb.Append("  <country>").Append(Xml(item.Area)).Append("</country>\n");
                if (!string.IsNullOrWhiteSpace(item.Remarks))
                    sb.Append("  <tagline>").Append(Xml(item.Remarks)).Append("</tagline>\n");
                if (!string.IsNullOrWhiteSpace(item.Content))
                    sb.Append("  <plot>").Append(Xml(item.Content)).Append("</plot>\n");
                if (!string.IsNullOrWhiteSpace(item.Director))
                    sb.Append("  <director>").Append(Xml(item.Director)).Append("</director>\n");
                foreach (var a in SplitNames(item.Actor))
                {
                    sb.Append("  <actor><name>").Append(Xml(a)).Append("</name></actor>\n");
                }
                // 标记来源，便于日后排查「这条是哪个站来的」
                sb.Append("  <uniqueid type=\"tvbox\" default=\"true\">")
                  .Append(Xml("tvbox-" + (item.Id ?? ""))).Append("</uniqueid>\n");
                sb.Append(isSeries ? "</tvshow>\n" : "</movie>\n");

                var file = Path.Combine(dir, isSeries ? "tvshow.nfo" : "movie.nfo");
                var content = sb.ToString();
                if (File.Exists(file) && File.ReadAllText(file) == content) return;
                Directory.CreateDirectory(dir);
                File.WriteAllText(file, content, new UTF8Encoding(false));
            }
            catch (Exception ex)
            {
                _log("写 nfo 失败 " + dir + ": " + ex.Message);
            }
        }

        /// <summary>下载海报。已经有 poster.png 的就跳过，避免每轮重复下载。</summary>
        private void EnsurePoster(string dir, string picUrl)
        {
            if (!DownloadPoster || FetchImage == null || string.IsNullOrWhiteSpace(dir)) return;
            if (string.IsNullOrWhiteSpace(picUrl)) return;
            try
            {
                var existing = Directory.Exists(dir)
                    ? Directory.GetFiles(dir, "poster.*").FirstOrDefault()
                    : null;
                if (!string.IsNullOrEmpty(existing) && new FileInfo(existing).Length > 1024) return;

                var bytes = FetchImage(picUrl);
                if (bytes == null || bytes.Length < 1024) return;
                if (PosterMaxBytes > 0 && bytes.Length > PosterMaxBytes) return;

                // 按魔数决定扩展名，免得 png 存成 .jpg 让 Emby 认错
                var ext = ".jpg";
                if (bytes.Length > 8 && bytes[0] == 0x89 && bytes[1] == 0x50) ext = ".png";
                else if (bytes.Length > 12 && bytes[0] == (byte)'R' && bytes[1] == (byte)'I'
                         && bytes[8] == (byte)'W' && bytes[9] == (byte)'E') ext = ".webp";

                Directory.CreateDirectory(dir);
                File.WriteAllBytes(Path.Combine(dir, "poster" + ext), bytes);
            }
            catch (Exception ex)
            {
                _log("下载海报失败 " + picUrl + ": " + ex.Message);
            }
        }

        private static string Xml(string s)
        {
            if (string.IsNullOrEmpty(s)) return string.Empty;
            return s.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;")
                    .Replace("\"", "&quot;").Replace("'", "&apos;");
        }

        /// <summary>演员串常见分隔符：逗号 / 顿号 / 斜杠 / 竖线。</summary>
        private static IEnumerable<string> SplitNames(string s)
        {
            if (string.IsNullOrWhiteSpace(s)) yield break;
            foreach (var part in s.Split(new[] { ',', '，', '、', '/', '|', ';', '；' },
                         StringSplitOptions.RemoveEmptyEntries))
            {
                var t = part.Trim();
                if (t.Length > 0 && t.Length <= 40) yield return t;
            }
        }

        private WriteOutcome WriteStrmFile(string file, string url)
        {
            var target = UrlRewriter != null ? UrlRewriter(url) : url;
            var content = target + "\n";
            try
            {
                var dir = Path.GetDirectoryName(file);
                if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
                    Directory.CreateDirectory(dir);

                if (File.Exists(file))
                {
                    var existing = File.ReadAllText(file);
                    if (string.Equals(existing, content, StringComparison.Ordinal))
                        return new WriteOutcome { Path = file };
                    File.WriteAllText(file, content, new UTF8Encoding(false));
                    return new WriteOutcome { Updated = true, Path = file };
                }

                File.WriteAllText(file, content, new UTF8Encoding(false));
                return new WriteOutcome { Created = true, Path = file };
            }
            catch (Exception ex)
            {
                LastError = ex.GetType().Name + ": " + ex.Message;
                _log($"写 strm 失败 {file}: {ex.Message}");
                return default;
            }
        }

        // ───────────────────── 清理 ─────────────────────

        /// <summary>
        /// 删掉不再属于任何条目的 .strm，并顺手清空目录。
        /// 只在我们自己的 movies/ tv/ 两个根下面操作，绝不碰别的地方。
        /// </summary>
        public int CleanupOrphans(HashSet<string> keep, out int removedDirs)
        {
            removedDirs = 0;
            if (keep == null) return 0;

            // 只有清单里出现过、这次又不需要了的文件才删；清单缺失时退化为「只删带本插件代理标记的」
            var owned = LoadManifest();
            bool useManifest = owned.Count > 0;
            var removed = 0;
            foreach (var root in new[] { MoviesRoot, TvRoot })
            {
                if (!Directory.Exists(root)) continue;
                string[] files;
                try
                {
                    files = Directory.GetFiles(root, "*.strm", SearchOption.AllDirectories);
                }
                catch (Exception ex)
                {
                    _log($"扫描 {root} 失败: {ex.Message}");
                    continue;
                }

                // 防误删保险：一次性删太多通常意味着路径/命名规则被改动过（换根目录、
                // 改了命名方案），直接全删会让 Emby 整个库变空。超过上限就停手并报错。
                int cap = MaxDeletePerRun;
                int wouldDelete = files.Count(f => !keep.Contains(f)
                        && (!useManifest || owned.Contains(f)));
                if (cap > 0 && wouldDelete > cap)
                {
                    DeletesSkipped = wouldDelete;
                    LastError = $"本次要删 {wouldDelete} 个 strm，超过上限 {cap}，已跳过清理"
                              + "（确认无误后把「单次删除上限」调大或临时设为 0）";
                    _log(LastError);
                    return 0;
                }

                foreach (var f in files)
                {
                    if (keep.Contains(f)) continue;
                    if (useManifest && !owned.Contains(f)) continue;      // 不是我们写的，别碰
                    if (!useManifest)
                    {
                        // 没清单时保守一点：只删内容明显是本插件生成的
                        try
                        {
                            var head = File.ReadAllText(f);
                            if (head.IndexOf("/TvBox/Stream/", StringComparison.Ordinal) < 0) continue;
                        }
                        catch { continue; }
                    }
                    try
                    {
                        File.Delete(f);
                        removed++;
                    }
                    catch (Exception ex)
                    {
                        _log($"删除 {f} 失败: {ex.Message}");
                    }
                }

                // 自底向上删空目录
                try
                {
                    foreach (var d in Directory.GetDirectories(root, "*", SearchOption.AllDirectories)
                                               .OrderByDescending(x => x.Length))
                    {
                        if (Directory.Exists(d) && !Directory.EnumerateFileSystemEntries(d).Any())
                        {
                            Directory.Delete(d);
                            removedDirs++;
                        }
                    }
                }
                catch { /* 目录清理失败不影响主流程 */ }
            }
            return removed;
        }

        // ───────────────────── 命名工具（Emby 规范） ─────────────────────

        private static readonly System.Text.RegularExpressions.Regex ReSeasonEp =
            new System.Text.RegularExpressions.Regex(@"[Ss](\d{1,2})\s*[Ee](\d{1,4})",
                System.Text.RegularExpressions.RegexOptions.Compiled);
        private static readonly System.Text.RegularExpressions.Regex ReSeasonCn =
            new System.Text.RegularExpressions.Regex(@"第\s*([0-9一二三四五六七八九十]+)\s*季",
                System.Text.RegularExpressions.RegexOptions.Compiled);
        private static readonly System.Text.RegularExpressions.Regex ReSeasonEn =
            new System.Text.RegularExpressions.Regex(@"\b[Ss]eason\s*(\d{1,2})\b",
                System.Text.RegularExpressions.RegexOptions.Compiled);
        private static readonly System.Text.RegularExpressions.Regex ReEpCn =
            new System.Text.RegularExpressions.Regex(@"第\s*([0-9一二三四五六七八九十百千]+)\s*[集话期回]",
                System.Text.RegularExpressions.RegexOptions.Compiled);
        private static readonly System.Text.RegularExpressions.Regex ReEpEn =
            new System.Text.RegularExpressions.Regex(@"\b[Ee][Pp]?\s*(\d{1,4})\b",
                System.Text.RegularExpressions.RegexOptions.Compiled);
        private static readonly System.Text.RegularExpressions.Regex ReEpPlain =
            new System.Text.RegularExpressions.Regex(@"^\s*(\d{1,4})\s*$",
                System.Text.RegularExpressions.RegexOptions.Compiled);
        private static readonly System.Text.RegularExpressions.Regex ReEpNumPrefix =
            new System.Text.RegularExpressions.Regex(@"^\s*(\d{1,4})[\s\.\-、:_]+",
                System.Text.RegularExpressions.RegexOptions.Compiled);

        /// <summary>这些集名不是真正的集名，只是「整片」的别名。</summary>
        private static readonly HashSet<string> PlaceholderEpisodes = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "正片", "原片", "正片1", "高清", "超清", "蓝光", "完整版", "未删减", "未删减版",
            "国语", "粤语", "中字", "双语", "HD", "BD", "TC", "TS", "预告", "花絮", "彩蛋", "片花", "PV"
        };

        /// <summary>
        /// 「国语中字」「粤语中字」「繁中」这类是同一部片的语言版本，不是分集。
        /// 实测《火拼》这类条目会把国语/粤语两条地址列成一个列表，按集处理会变成
        /// S01E01（国语中字）/ S01E02（粤语中字），Emby 刮削必然错乱。
        /// </summary>
        private static readonly System.Text.RegularExpressions.Regex ReLanguageVersion =
            new System.Text.RegularExpressions.Regex(
                @"^(?:(?:国|粤|英|日|韩|泰|法|德|俄)语?(?:中字|字幕|版)?|双语(?:中字)?|中字|无字|繁中|简中|中英(?:双字|字幕)?|中文字幕|HD中字|BD中字|[Hh][Dd]|[Bb][Dd]|4K)$",
                System.Text.RegularExpressions.RegexOptions.Compiled);

        /// <summary>判断某条分集名是不是「正片/原片/国语中字」这类整片别名。</summary>
        public static bool IsPlaceholderEpisode(string epName)
        {
            if (string.IsNullOrWhiteSpace(epName)) return false;   // 没名字 = 普通分集
            var k = System.Text.RegularExpressions.Regex.Replace(epName.Trim(), @"\s+", "");
            if (PlaceholderEpisodes.Contains(k)) return true;      // 正片 / 原片 / HD …
            return ReLanguageVersion.IsMatch(k);                   // 国语中字 / 粤语中字 / 繁中 …
        }

        /// <summary>片名 + (年份)。年份优先取 vod_year，其次从片名末尾的括号里抠。</summary>
        public static string TitleWithYear(string name, string year, string remarks = null)
        {
            var n = CleanTitle(name);
            // 年份兜底顺序：vod_year → 片名里的年份 → 备注里的年份。
            // 很多源 vod_year 是空的（实测《骤雨》《遇见你》都没有），
            // 备注却常写「2024」「2024-10-19」，不兜底就只能写成无年份目录，刮削直接漂。
            var y = ExtractYear(year) ?? YearFromTitle(name) ?? ExtractYear(remarks);
            return string.IsNullOrEmpty(y) ? n : $"{n} ({y})";
        }

        /// <summary>
        /// 从自由文本里认季号：第2季 / 第二季 / Season 2。
        /// 只给「整条都是同一季」的条目用（备注写「第二季」，集名里却不带季号）。
        /// </summary>
        public static int SeasonFromText(string t)
        {
            if (string.IsNullOrWhiteSpace(t)) return 0;
            var m = ReSeasonCn.Match(t);
            if (m.Success)
            {
                var n = CnToInt(m.Groups[1].Value);
                if (n > 0) return n;
            }
            m = ReSeasonEn.Match(t);
            if (m.Success && int.TryParse(m.Groups[1].Value, out var n2) && n2 > 0) return n2;
            return 0;
        }

        /// <summary>
        /// 从集名解析季号 / 集号，并返回「干净的集名」（去掉 第N集 / SxxEyy 这类与文件名重复的部分）。
        /// 返回 null 表示这集没有额外集名（文件名只写 SxxEyy）。
        /// </summary>
        public static string CleanEpisodeTitle(string raw, out int season, out int num)
        {
            season = 0;
            num = 0;
            var s0 = (raw ?? "").Trim();
            if (s0.Length == 0) return null;

            var m = ReSeasonEp.Match(s0);
            if (m.Success)
            {
                int.TryParse(m.Groups[1].Value, out season);
                int.TryParse(m.Groups[2].Value, out num);
            }
            if (season <= 0)
            {
                var a = ReSeasonCn.Match(s0);
                if (a.Success) season = CnToInt(a.Groups[1].Value);
                if (season <= 0)
                {
                    var b = ReSeasonEn.Match(s0);
                    if (b.Success) int.TryParse(b.Groups[1].Value, out season);
                }
            }
            if (num <= 0)
            {
                var c = ReEpCn.Match(s0);
                if (c.Success) num = CnToInt(c.Groups[1].Value);
                if (num <= 0)
                {
                    var d = ReEpEn.Match(s0);
                    if (d.Success) int.TryParse(d.Groups[1].Value, out num);
                }
                if (num <= 0)
                {
                    var e = ReEpPlain.Match(s0);
                    if (e.Success) int.TryParse(e.Groups[1].Value, out num);
                }
                if (num <= 0)
                {
                    var f = ReEpNumPrefix.Match(s0);
                    if (f.Success) int.TryParse(f.Groups[1].Value, out num);
                }
            }

            var t = s0;
            foreach (var re in new[] { ReSeasonEp, ReSeasonCn, ReSeasonEn, ReEpCn, ReEpEn })
                t = re.Replace(t, " ");
            t = t.Trim(' ', '-', '—', '_', '.', '、', ':', '：', '|', '/', '#');
            t = System.Text.RegularExpressions.Regex.Replace(t, @"\s{2,}", " ").Trim();
            // 集名本来就是「2」「12.」这类纯数字：集号已进文件名，别再拼一遍「- 2」
            if (System.Text.RegularExpressions.Regex.IsMatch(t, @"^\d{1,4}[\.、]?$")) return null;
            t = ReEpNumPrefix.Replace(t, "").Trim(' ', '-', '_', '.', '、', ':', '：');
            if (t.Length == 0) return null;
            var k2 = System.Text.RegularExpressions.Regex.Replace(t, @"\s+", "");
            if (PlaceholderEpisodes.Contains(k2) || ReLanguageVersion.IsMatch(k2)) return null;
            if (t.Length > 60) t = t.Substring(0, 60).Trim();
            return t;
        }

        /// <summary>中文数字转整数（一 ~ 九千九百九十九，覆盖 第X集/第X季 的常见写法）。</summary>
        public static int CnToInt(string s)
        {
            if (string.IsNullOrEmpty(s)) return 0;
            if (int.TryParse(s, out var direct)) return direct;
            const string digits = "零一二三四五六七八九";
            int total = 0, cur = 0;
            foreach (var ch in s)
            {
                if (ch == '十') { total += (cur == 0 ? 1 : cur) * 10; cur = 0; }
                else if (ch == '百') { total += (cur == 0 ? 1 : cur) * 100; cur = 0; }
                else if (ch == '千') { total += (cur == 0 ? 1 : cur) * 1000; cur = 0; }
                else
                {
                    var i = digits.IndexOf(ch);
                    if (i >= 0) cur = i;
                }
            }
            return total + cur;
        }

        private static readonly System.Text.RegularExpressions.Regex NoiseBracket =
            new System.Text.RegularExpressions.Regex(
                @"[\(\[（【]\s*(?:HD|BD|高清|超清|蓝光|4K|8K|1080P?|2160P?|720P?|国语|粤语|英语|中字|中文字幕|双字|双语|未删减|导演剪辑|完整版|TC|TS|WEB[- ]?DL|HDTV|REMUX)\s*[\)\]）】]",
                System.Text.RegularExpressions.RegexOptions.IgnoreCase | System.Text.RegularExpressions.RegexOptions.Compiled);
        private static readonly System.Text.RegularExpressions.Regex NoiseTailWord =
            new System.Text.RegularExpressions.Regex(
                @"[\s\-_·|]*\b(?:HD|BD|4K|8K|1080P|2160P|WEB[- ]?DL|HDTV|REMUX|TC|TS)\b[\s\-_·|]*$",
                System.Text.RegularExpressions.RegexOptions.IgnoreCase | System.Text.RegularExpressions.RegexOptions.Compiled);
        // 「更新至20集 / 全40集」这些位置不固定（常被 (年份)、[HD] 隔开），所以不锚定结尾
        private static readonly System.Text.RegularExpressions.Regex NoiseStatus =
            new System.Text.RegularExpressions.Regex(
                @"[\s\-_·|]*(?:更新至\s*\d+\s*[集话期]|全\s*\d+\s*集|已?完结|连载中|高清版|蓝光版|国语版|中字版|抢先版)",
                System.Text.RegularExpressions.RegexOptions.Compiled);
        private static readonly System.Text.RegularExpressions.Regex YearInBrackets =
            new System.Text.RegularExpressions.Regex(@"[\(（]\s*((?:19|20)\d{2})\s*[\)）]\s*$",
                System.Text.RegularExpressions.RegexOptions.Compiled);

        /// <summary>
        /// 片名清洗：去 emoji、去「HD/蓝光/国语/中字/更新至N集」这类噪声尾巴。
        /// Emby 拿这个名字去 TMDB 匹配，带装饰字符基本必失败。
        /// </summary>
        public static string CleanTitle(string name)
        {
            if (string.IsNullOrWhiteSpace(name)) return "未命名";
            var s = StripEmoji(name).Trim();
            for (int i = 0; i < 5; i++)
            {
                var before = s;
                s = NoiseBracket.Replace(s, "");
                s = NoiseTailWord.Replace(s, "");
                s = NoiseStatus.Replace(s, "");
                s = YearInBrackets.Replace(s, "");   // 年份由 TitleWithYear 统一补，避免出现两次
                s = s.Trim(' ', '-', '_', '·', '|', '/', '、', ':', '：');
                s = System.Text.RegularExpressions.Regex.Replace(s, @"\s{2,}", " ");
                if (s == before) break;
            }
            return s.Length == 0 ? "未命名" : s;
        }

        /// <summary>去掉 emoji 和各种装饰符号（Emby 匹配片名时这些字符是纯噪声）。</summary>
        public static string StripEmoji(string s)
        {
            if (string.IsNullOrEmpty(s)) return string.Empty;
            var sb = new StringBuilder(s.Length);
            foreach (var ch in s)
            {
                var cat = char.GetUnicodeCategory(ch);
                if (cat == System.Globalization.UnicodeCategory.OtherSymbol
                    || cat == System.Globalization.UnicodeCategory.OtherNotAssigned
                    || cat == System.Globalization.UnicodeCategory.Format
                    || cat == System.Globalization.UnicodeCategory.PrivateUse
                    || cat == System.Globalization.UnicodeCategory.Surrogate)
                    continue;
                sb.Append(ch);
            }
            return sb.ToString();
        }

        private static string YearFromTitle(string name)
        {
            if (string.IsNullOrWhiteSpace(name)) return null;
            var m = YearInBrackets.Match(name.Trim());
            return m.Success ? m.Groups[1].Value : null;
        }

        private static string WithYear(string name, string year) => TitleWithYear(name, year);

        /// <summary>片名里本身可能带 "(2023)"，剥掉避免重复。</summary>
        private static string StripYear(string name)
        {
            if (string.IsNullOrWhiteSpace(name)) return "未命名";
            var n = name.Trim();
            var m = System.Text.RegularExpressions.Regex.Match(n, @"\s*[\(（]\s*(19|20)\d{2}\s*[\)）]\s*$");
            if (m.Success) n = n.Substring(0, m.Index).Trim();
            return string.IsNullOrEmpty(n) ? "未命名" : n;
        }

        private static string ExtractYear(string year)
        {
            if (string.IsNullOrWhiteSpace(year)) return null;
            var m = System.Text.RegularExpressions.Regex.Match(year, @"(19|20)\d{2}");
            return m.Success ? m.Value : null;
        }

        private static readonly char[] Invalid = { '<', '>', ':', '"', '/', '\\', '|', '?', '*' };

        /// <summary>
        /// 文件名净化。Windows / Linux / SMB 都要能存，所以按最严格的标准来。
        /// 注意：Emby 会把 "Name (Year)" 当作匹配提示，所以括号要保留。
        /// </summary>
        public static string SafeName(string name)
        {
            if (string.IsNullOrWhiteSpace(name)) return "未命名";
            name = StripEmoji(name);
            if (name.Length == 0) return "未命名";
            var sb = new StringBuilder(name.Length);
            foreach (var ch in name)
            {
                if (Array.IndexOf(Invalid, ch) >= 0) continue;
                if (char.IsControl(ch)) continue;
                sb.Append(ch);
            }
            var s = sb.ToString().Trim().Trim('.');
            // 折叠连续空白
            s = System.Text.RegularExpressions.Regex.Replace(s, @"\s{2,}", " ");
            if (s.Length == 0) s = "未命名";
            // 路径长度控制：Emby 在一些客户端上会被超长路径搞坏
            if (s.Length > 100) s = s.Substring(0, 100).Trim();
            return s;
        }
    }
}
