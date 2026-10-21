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
    /// 目录结构（两个库根分开，因为 Emby 的「电影」和「电视剧」库类型不能混）：
    ///   &lt;root&gt;/movies/&lt;站点名&gt;/&lt;片名 (年份)&gt;/&lt;片名&gt;.strm
    ///   &lt;root&gt;/tv/&lt;站点名&gt;/&lt;剧名 (年份)&gt;/Season 01/&lt;剧名&gt; - S01E01 - &lt;集名&gt;.strm
    ///
    /// 片名带年份是为了让 Emby 的刮削器能可靠匹配（否则同名不同版全串）。
    /// </summary>
    public class StrmWriter
    {
        private readonly string _root;
        private readonly Action<string> _log;

        /// <summary>把原始播放地址改写成实际写进 strm 的地址（用于挂代理）。</summary>
        public Func<string, string> UrlRewriter { get; set; }

        public StrmWriter(string root, Action<string> log = null)
        {
            _root = root;
            _log = log ?? (_ => { });
        }

        public string MoviesRoot => Path.Combine(_root, "movies");
        public string TvRoot => Path.Combine(_root, "tv");

        public string Root => _root;

        // ───────────────────── 写入 ─────────────────────

        public WriteOutcome WriteMovie(string siteName, VodItem item, VodLine line)
        {
            var ep = line?.Episodes?.FirstOrDefault();
            if (ep == null) return default;

            var folder = SafeName(WithYear(item.Name, item.Year));
            var dir = Path.Combine(MoviesRoot, SafeName(siteName), folder);
            var file = Path.Combine(dir, SafeName(StripYear(item.Name)) + ".strm");
            return WriteStrmFile(file, ep.Url);
        }

        public List<WriteOutcome> WriteSeries(string siteName, VodItem item, VodLine line)
        {
            var results = new List<WriteOutcome>();
            if (line?.Episodes == null || line.Episodes.Count == 0) return results;

            var showName = StripYear(item.Name);
            var showFolder = SafeName(WithYear(item.Name, item.Year));
            var showDir = Path.Combine(TvRoot, SafeName(siteName), showFolder);
            var seasonDir = Path.Combine(showDir, "Season 01");

            for (int i = 0; i < line.Episodes.Count; i++)
            {
                var ep = line.Episodes[i];
                var num = i + 1;
                var epTitle = string.IsNullOrWhiteSpace(ep.Name) ? "第" + num + "集" : ep.Name.Trim();
                // 集名里常带 # / 这类字符，去掉后拼进文件名
                var fileName = $"{SafeName(showName)} - S01E{num:00} - {SafeName(epTitle)}.strm";
                var file = Path.Combine(seasonDir, fileName);
                results.Add(WriteStrmFile(file, ep.Url));
            }
            return results;
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

                foreach (var f in files)
                {
                    if (keep.Contains(f)) continue;
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

        // ───────────────────── 命名工具 ─────────────────────

        private static string WithYear(string name, string year)
        {
            var n = StripYear(name);
            var y = ExtractYear(year);
            return string.IsNullOrEmpty(y) ? n : $"{n} ({y})";
        }

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
