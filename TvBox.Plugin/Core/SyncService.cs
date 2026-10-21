using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace TvBox.Plugin.Core
{
    /// <summary>同步进度（供配置页轮询）。</summary>
    public class SyncProgress
    {
        public bool Running { get; set; }
        public string Stage { get; set; } = "";
        public string CurrentSite { get; set; } = "";
        public int SiteIndex { get; set; }
        public int SiteTotal { get; set; }
        public int ItemsWritten { get; set; }
        public int EpisodesWritten { get; set; }
        public int ItemsSkipped { get; set; }
        public int Errors { get; set; }
        public string LastError { get; set; } = "";
        public DateTime StartedUtc { get; set; }
        public DateTime FinishedUtc { get; set; }
    }

    public class SyncSummary
    {
        public int Sites { get; set; }
        public int SitesFailed { get; set; }
        public int Items { get; set; }
        public int Episodes { get; set; }
        public int Removed { get; set; }
        public int Errors { get; set; }
        public string Detail { get; set; } = "";
    }

    /// <summary>
    /// 同步编排：拉配置 → 逐站点拉分类/列表/详情 → 写 strm → 清理孤儿 → 通知 Emby 刷新。
    /// 单一实例运行（_gate），避免定时任务和手动触发撞车。
    /// </summary>
    public class SyncService
    {
        private readonly Action<string> _log;
        private readonly SemaphoreSlim _gate = new SemaphoreSlim(1, 1);
        private readonly object _stateLock = new object();

        public SyncProgress Progress { get; } = new SyncProgress();

        public SyncService(Action<string> log = null)
        {
            _log = log ?? (_ => { });
        }

        public bool IsRunning
        {
            get { lock (_stateLock) return Progress.Running; }
        }

        private void Update(Action<SyncProgress> mutate)
        {
            lock (_stateLock) mutate(Progress);
        }

        public static HttpClient CreateClient(PluginConfiguration cfg, string forUserAgent = null)
        {
            var handler = new HttpClientHandler
            {
                AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate,
                AllowAutoRedirect = true,
                MaxAutomaticRedirections = 5,
            };
            var client = new HttpClient(handler)
            {
                Timeout = TimeSpan.FromSeconds(Math.Max(5, cfg.TimeoutSeconds)),
            };
            var ua = string.IsNullOrWhiteSpace(forUserAgent) ? cfg.UserAgent : forUserAgent;
            if (!string.IsNullOrWhiteSpace(ua))
                client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", ua);
            client.DefaultRequestHeaders.TryAddWithoutValidation("Accept", "*/*");
            return client;
        }

        /// <summary>
        /// 执行一次同步。返回 false 表示已有一个同步在跑，本次被忽略。
        /// </summary>
        public async Task<bool> RunAsync(
            PluginConfiguration cfg,
            string outputRoot,
            CancellationToken ct,
            Action saveConfig = null,
            string embyBaseUrlForProxy = null)
        {
            if (!await _gate.WaitAsync(0, ct).ConfigureAwait(false))
            {
                _log("已有同步任务在运行，本次跳过");
                return false;
            }

            try
            {
                Update(p =>
                {
                    p.Running = true;
                    p.Stage = "读取配置";
                    p.CurrentSite = "";
                    p.SiteIndex = 0;
                    p.SiteTotal = 0;
                    p.ItemsWritten = 0;
                    p.EpisodesWritten = 0;
                    p.ItemsSkipped = 0;
                    p.Errors = 0;
                    p.LastError = "";
                    p.StartedUtc = DateTime.UtcNow;
                    p.FinishedUtc = DateTime.MinValue;
                });

                var summary = new SyncSummary();
                var keep = new HashSet<string>(StringComparer.Ordinal);

                using (var http = CreateClient(cfg))
                {
                    // ── 1. 拉配置 ──
                    var loader = new TvBoxConfigLoader(http, m => _log("[config] " + m));
                    var doc = await loader.LoadAsync(cfg.ConfigUrl, ct).ConfigureAwait(false);
                    foreach (var w in doc.LoadWarnings) _log("[config] 警告: " + w);

                    // ── 2. 过滤站点 ──
                    var sites = SelectSites(doc.Sites, cfg).ToList();
                    summary.Sites = sites.Count;
                    _log($"配置里共 {doc.Sites.Count} 个站点，本次要同步 {sites.Count} 个");

                    if (sites.Count == 0)
                    {
                        summary.Detail = "没有可同步的站点（检查配置地址，或用白名单/黑名单过滤得太狠）";
                        Finish(summary, cfg, saveConfig);
                        return true;
                    }

                    Update(p => { p.SiteTotal = sites.Count; p.Stage = "同步站点"; });

                    var writer = new StrmWriter(outputRoot, m => _log("[strm] " + m));
                    if (cfg.UseProxy && !string.IsNullOrWhiteSpace(embyBaseUrlForProxy))
                    {
                        var baseUrl = embyBaseUrlForProxy.TrimEnd('/');
                        writer.UrlRewriter = u =>
                            baseUrl + TvBox.Plugin.Api.TvBoxApi.BuildProxyPath(u, cfg.Referer);
                        _log("播放地址将改写为本机代理: " + baseUrl + "/TvBox/Stream");
                    }
                    else if (cfg.UseProxy)
                    {
                        _log("开启了代理但没填 Emby 访问地址，strm 里直接写原始地址");
                    }

                    // ── 3. 逐站点同步 ──
                    int siteIdx = 0;
                    foreach (var site in sites)
                    {
                        ct.ThrowIfCancellationRequested();
                        siteIdx++;
                        Update(p => { p.SiteIndex = siteIdx; p.CurrentSite = site.DisplayName; });
                        _log($"── [{siteIdx}/{sites.Count}] {site.DisplayName} ({site.Api})");

                        try
                        {
                            var n = await SyncOneSiteAsync(site, cfg, writer, keep, ct, summary).ConfigureAwait(false);
                            _log($"   {site.DisplayName}: {n} 个条目");
                        }
                        catch (OperationCanceledException) { throw; }
                        catch (Exception ex)
                        {
                            summary.SitesFailed++;
                            summary.Errors++;
                            _log($"   站点失败: {ex.Message}");
                            Update(p => { p.Errors++; p.LastError = site.DisplayName + ": " + ex.Message; });
                        }
                    }

                    // ── 4. 清理孤儿 ──
                    if (cfg.CleanupOrphans)
                    {
                        Update(p => p.Stage = "清理失效条目");
                        var removed = writer.CleanupOrphans(keep, out var removedDirs);
                        summary.Removed = removed;
                        _log($"清理了 {removed} 个失效 strm、{removedDirs} 个空目录");
                    }
                }

                summary.Detail = $"站点 {summary.Sites}（失败 {summary.SitesFailed}），条目 {summary.Items}，分集 {summary.Episodes}，清理 {summary.Removed}，错误 {summary.Errors}";
                Finish(summary, cfg, saveConfig);
                return true;
            }
            finally
            {
                Update(p => { p.Running = false; p.FinishedUtc = DateTime.UtcNow; });
                _gate.Release();
            }
        }

        private void Finish(SyncSummary summary, PluginConfiguration cfg, Action saveConfig)
        {
            cfg.LastSyncUtc = DateTime.UtcNow;
            cfg.LastSyncSummary = summary.Detail;
            try { saveConfig?.Invoke(); } catch (Exception ex) { _log("保存配置失败: " + ex.Message); }
            Update(p => { p.Stage = "完成"; });
            _log("同步完成: " + summary.Detail);
        }

        /// <summary>按白名单 / 黑名单 / 名字过滤，挑出本次要处理的站点。</summary>
        public static IEnumerable<TvBoxSite> SelectSites(IEnumerable<TvBoxSite> all, PluginConfiguration cfg)
        {
            var white = SplitCsv(cfg.SiteWhitelist);
            var black = SplitCsv(cfg.SiteBlacklist);
            var nameFilters = SplitCsv(cfg.SiteNameFilter);
            var dead = new HashSet<string>(cfg.DeadSites ?? new List<string>(), StringComparer.OrdinalIgnoreCase);

            foreach (var s in all)
            {
                if (s == null || !s.Supported) continue;
                if (dead.Contains(s.Key ?? "")) continue;
                if (black.Count > 0 && Contains(black, s.Key)) continue;
                if (white.Count > 0 && !Contains(white, s.Key)) continue;
                if (nameFilters.Count > 0 &&
                    !nameFilters.Any(f => (s.DisplayName ?? "").IndexOf(f, StringComparison.OrdinalIgnoreCase) >= 0))
                    continue;
                yield return s;
            }
        }

        private static List<string> SplitCsv(string s)
        {
            if (string.IsNullOrWhiteSpace(s)) return new List<string>();
            return s.Split(new[] { ',', '，', ';', '；' }, StringSplitOptions.RemoveEmptyEntries)
                    .Select(x => x.Trim())
                    .Where(x => x.Length > 0)
                    .ToList();
        }

        private static bool Contains(List<string> set, string key) =>
            key != null && set.Any(x => string.Equals(x, key, StringComparison.OrdinalIgnoreCase));

        // ───────────────────── 单站点同步 ─────────────────────

        private async Task<int> SyncOneSiteAsync(
            TvBoxSite site,
            PluginConfiguration cfg,
            StrmWriter writer,
            HashSet<string> keep,
            CancellationToken ct,
            SyncSummary summary)
        {
            using (var http = CreateClient(cfg))
            {
                var client = new CmsClient(http, m => _log("     " + m), cfg.Referer);

                var cats = await client.GetCategoriesAsync(site, ct).ConfigureAwait(false);
                if (cats.Count == 0)
                {
                    _log("     拿不到分类，跳过");
                    return 0;
                }

                int written = 0;
                int maxPages = Math.Max(1, cfg.MaxPagesPerCategory);

                foreach (var cat in cats)
                {
                    ct.ThrowIfCancellationRequested();

                    var ids = new List<string>();
                    for (int page = 1; page <= maxPages; page++)
                    {
                        PagedResult pr;
                        try
                        {
                            pr = await client.GetListAsync(site, cat.Id, page, ct).ConfigureAwait(false);
                        }
                        catch (OperationCanceledException) { throw; }
                        catch (Exception ex)
                        {
                            _log($"     分类 {cat.Name} 第 {page} 页失败: {ex.Message}");
                            summary.Errors++;
                            break;
                        }

                        foreach (var it in pr.Items)
                            if (!string.IsNullOrEmpty(it.Id)) ids.Add(it.Id);

                        if (pr.Items.Count == 0) break;
                        if (pr.PageCount > 0 && page >= pr.PageCount) break;
                        await DelayAsync(cfg, ct).ConfigureAwait(false);
                    }

                    if (ids.Count == 0) continue;

                    // 去重后分批拉详情（ac=detail 支持多 id 逗号分隔）
                    var distinct = ids.Distinct().ToList();
                    foreach (var batch in Chunk(distinct, 20))
                    {
                        ct.ThrowIfCancellationRequested();
                        List<VodItem> details;
                        try
                        {
                            details = await client.GetDetailAsync(site, batch, ct).ConfigureAwait(false);
                        }
                        catch (OperationCanceledException) { throw; }
                        catch (Exception ex)
                        {
                            _log($"     拉详情失败 ({batch.Count} 个): {ex.Message}");
                            summary.Errors++;
                            continue;
                        }

                        foreach (var item in details)
                        {
                            var line = PlayUrlParser.PickBestLine(item);
                            if (line == null) continue;

                            if (item.IsSeries)
                            {
                                var outs = writer.WriteSeries(site.DisplayName, item, line);
                                var added = outs.Count(o => o.Created || o.Updated);
                                foreach (var o in outs)
                                    if (!string.IsNullOrEmpty(o.Path)) keep.Add(o.Path);
                                if (added > 0)
                                {
                                    written++;
                                    summary.Items++;
                                    summary.Episodes += added;
                                    Update(p => { p.ItemsWritten++; p.EpisodesWritten += added; });
                                }
                                else
                                {
                                    summary.Items++;
                                    Update(p => p.ItemsSkipped++);
                                }
                            }
                            else
                            {
                                var o = writer.WriteMovie(site.DisplayName, item, line);
                                if (!string.IsNullOrEmpty(o.Path)) keep.Add(o.Path);
                                if (o.Created || o.Updated)
                                {
                                    written++;
                                    summary.Items++;
                                    summary.Episodes++;
                                    Update(p => { p.ItemsWritten++; p.EpisodesWritten++; });
                                }
                                else
                                {
                                    summary.Items++;
                                    Update(p => p.ItemsSkipped++);
                                }
                            }

                            if (cfg.MaxItemsPerRun > 0 && summary.Items >= cfg.MaxItemsPerRun)
                            {
                                _log($"     达到单次上限 {cfg.MaxItemsPerRun}，停止");
                                return written;
                            }
                        }

                        await DelayAsync(cfg, ct).ConfigureAwait(false);
                    }
                }

                return written;
            }
        }

        private static Task DelayAsync(PluginConfiguration cfg, CancellationToken ct)
        {
            var ms = Math.Max(0, cfg.RequestDelayMs);
            return ms == 0 ? Task.CompletedTask : Task.Delay(ms, ct);
        }

        private static IEnumerable<List<T>> Chunk<T>(List<T> src, int size)
        {
            for (int i = 0; i < src.Count; i += size)
                yield return src.GetRange(i, Math.Min(size, src.Count - i));
        }

        // ───────────────────── base64url ─────────────────────

        public static string Base64UrlEncode(string s)
        {
            if (s == null) return "";
            var bytes = System.Text.Encoding.UTF8.GetBytes(s);
            return Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        }

        public static string Base64UrlDecode(string s)
        {
            if (string.IsNullOrEmpty(s)) return null;
            var t = s.Replace('-', '+').Replace('_', '/');
            switch (t.Length % 4)
            {
                case 2: t += "=="; break;
                case 3: t += "="; break;
            }
            try { return System.Text.Encoding.UTF8.GetString(Convert.FromBase64String(t)); }
            catch { return null; }
        }
    }
}
