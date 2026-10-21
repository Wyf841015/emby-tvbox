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
        /// <summary>内容没变化、文件已在磁盘上的条目数（重复同步时大量出现）。</summary>
        public int Unchanged { get; set; }
        public int Errors { get; set; }
        /// <summary>处理过但没写进去的条目数（含写失败）。</summary>
        public int Skipped { get; set; }
        /// <summary>解析阶段被排除的源数量（网盘类 + 黑白名单过滤）。</summary>
        public int NetdiskExcluded { get; set; }
        public string Detail { get; set; } = "";
    }

    /// <summary>
    /// 同步编排：拉配置 → 逐站点拉分类/列表/详情 → 写 strm → 清理孤儿 → 通知 Emby 刷新。
    /// 单一实例运行（_gate），避免定时任务和手动触发撞车。
    /// </summary>
    public class SyncService
    {
        private readonly Action<string> _log;
        /// <summary>本轮增量状态（RunAsync 里创建）。</summary>
        private SyncState _state;
        private readonly SemaphoreSlim _gate = new SemaphoreSlim(1, 1);
        /// <summary>最近一次 strm 写失败的原因。放字段上是因为写入发生在站点循环的 helper 里，
        /// 而汇总代码在另一个作用域。</summary>
        private volatile string _lastWriteError;
        private CancellationTokenSource _cts;
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

        /// <summary>请求停止当前同步。正在进行的条目会跑完，下一个检查点退出。</summary>
        public bool RequestStop()
        {
            var cts = _cts;
            if (cts == null || cts.IsCancellationRequested) return false;
            try { cts.Cancel(); } catch { return false; }
            _log("收到停止请求，将在当前条目结束后退出");
            return true;
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
        /// <summary>
        /// 逐集校验，返回真正能播的分集列表。并发 6，整体失败不影响其它集。
        /// </summary>
        private static async Task<List<VodEpisode>> FilterPlayableEpisodesAsync(
            CmsClient client, List<VodEpisode> episodes, CancellationToken ct, Action<string> onDead)
        {
            var result = new List<VodEpisode>();
            if (episodes == null || episodes.Count == 0) return result;

            var sem = new SemaphoreSlim(6);
            var flags = new bool[episodes.Count];
            var tasks = new List<Task>();
            for (int i = 0; i < episodes.Count; i++)
            {
                int idx = i;
                tasks.Add(Task.Run(async () =>
                {
                    await sem.WaitAsync(ct).ConfigureAwait(false);
                    try
                    {
                        var (ok, why) = await client.VerifyPlayableAsync(episodes[idx]?.Url, ct).ConfigureAwait(false);
                        flags[idx] = ok;
                        if (!ok) onDead?.Invoke(why);
                    }
                    catch (OperationCanceledException)
                    {
                        if (ct.IsCancellationRequested) throw;   // 真·用户取消
                        flags[idx] = false;                      // 只是这一集超时
                    }
                    catch { flags[idx] = false; }
                    finally { sem.Release(); }
                }, ct));
            }
            try { await Task.WhenAll(tasks).ConfigureAwait(false); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            finally { sem.Dispose(); }

            for (int i = 0; i < episodes.Count; i++)
                if (flags[i]) result.Add(episodes[i]);
            return result;
        }

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

            _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            var token = _cts.Token;
            var summary = new SyncSummary();

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

                var keep = new HashSet<string>(StringComparer.Ordinal);
                var state = SyncState.Load(outputRoot, _log);
                state.Prune();
                if (cfg.IncrementalSync)
                    _log("增量同步: 开（有效期 " + Math.Max(0, cfg.IncrementalHours) + " 小时，已知条目 "
                         + state.Count + " 个）");
                _state = state;

                using (var http = CreateClient(cfg))
                {
                    // ── 1. 拉配置 ──
                    var loader = new TvBoxConfigLoader(http, m => _log("[config] " + m));
                    var doc = await loader.LoadAsync(cfg.ConfigUrl, token).ConfigureAwait(false);
                    foreach (var w in doc.LoadWarnings) _log("[config] 警告: " + w);

                    // 网盘名单必须在 ApplySpiderHub 之前取：改写后 site.Api 变成 hub 地址，
                    // 原始 csp_Xxx 信息就没了，兜底判定会失效。
                    HashSet<string> netdiskKeys = null;
                    if (cfg.ExcludeCloudDrive)
                    {
                        _logStatic = _log;
                        netdiskKeys = await FetchNetdiskKeysAsync(cfg.SpiderHubUrl, http, token).ConfigureAwait(false);
                        var fallback = doc.Sites.Count(s => LooksLikeNetdisk(s.Api, s.DisplayName ?? s.Name));
                        _log($"[netdisk] hub 报告 {netdiskKeys.Count} 个网盘源，名字兜底再识别 {fallback} 个，将在解析阶段排除");
                    }

                    // 站点页勾选的名单（用户按测试情况自己选），优先于本地白名单
                    HashSet<string> pickedSites = null;
                    if (!string.IsNullOrWhiteSpace(cfg.SpiderHubUrl))
                    {
                        _logStatic = _log;
                        pickedSites = await FetchSyncSitesAsync(cfg.SpiderHubUrl, http, token).ConfigureAwait(false);
                        if (pickedSites != null)
                            _log($"[sync] 站点页勾选了 {pickedSites.Count} 个站点"
                                 + (pickedSites.Count == 0 ? "（没勾，按全部直连站同步）" : ""));
                    }

                    var hubCount = TvBoxConfigLoader.ApplySpiderHub(doc, cfg.SpiderHubUrl);
                    if (hubCount > 0)
                        _log($"[hub] {hubCount} 个 spider 站点已改走中转: {cfg.SpiderHubUrl}");

                    // ── 2. 过滤站点 ──
                    var sites = SelectSites(doc.Sites, cfg, netdiskKeys, pickedSites).ToList();
                    summary.Sites = sites.Count;
                    summary.NetdiskExcluded = Math.Max(0, doc.Sites.Count(s => s.Supported) - sites.Count);
                    _log($"配置里共 {doc.Sites.Count} 个站点"
                         + (summary.NetdiskExcluded > 0 ? $"（已排除 {summary.NetdiskExcluded} 个网盘/被过滤源）" : "")
                         + $"，本次要同步 {sites.Count} 个");

                    if (sites.Count == 0)
                    {
                        summary.Detail = "没有可同步的站点（检查配置地址，或用白名单/黑名单过滤得太狠）";
                        Finish(summary, cfg, saveConfig);
                        return true;
                    }

                    Update(p => { p.SiteTotal = sites.Count; p.Stage = "同步站点"; });

                    var writer = new StrmWriter(outputRoot, m => _log("[strm] " + m));
                    writer.WriteNfo = cfg.WriteNfo;
                    writer.DownloadPoster = cfg.DownloadPoster;
                    writer.PosterMaxBytes = Math.Max(0, cfg.PosterMaxMb) * 1024 * 1024;
                    writer.MaxDeletePerRun = cfg.MaxDeletePerRun;
                    if (cfg.DownloadPoster)
                    {
                        // 海报下载：独立 HttpClient（短超时），失败不影响 strm 写入
                        var imgHttp = new HttpClient { Timeout = TimeSpan.FromSeconds(Math.Max(5, cfg.TimeoutSeconds)) };
                        try
                        {
                            if (!string.IsNullOrWhiteSpace(cfg.UserAgent))
                                imgHttp.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", cfg.UserAgent);
                        }
                        catch { }
                        writer.FetchImage = url => FetchImageBytes(imgHttp, url, writer.PosterMaxBytes);
                        _log("元数据: nfo=" + (cfg.WriteNfo ? "开" : "关")
                             + " 海报=" + (cfg.DownloadPoster ? "开" : "关"));
                    }
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
                            if (n > 0) await ReportSiteAsync(cfg, site.Key, true, ct).ConfigureAwait(false);
                        }
                        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                        catch (Exception ex)
                        {
                            summary.SitesFailed++;
                            summary.Errors++;
                            _log($"   站点失败: {ex.Message}");
                            await ReportSiteAsync(cfg, site.Key, false, ct).ConfigureAwait(false);
                            Update(p => { p.Errors++; p.LastError = site.DisplayName + ": " + ex.Message; });
                        }
                    }

                    // ── 4. 清理孤儿 ──
                    if (cfg.CleanupOrphans)
                    {
                        Update(p => p.Stage = "清理失效条目");
                        var removed = writer.CleanupOrphans(keep, out var removedDirs);
                        writer.SaveManifest(keep);          // 记下我们拥有的文件，下次清理以此为准
                        summary.Removed = removed;
                        _log($"清理了 {removed} 个失效 strm、{removedDirs} 个空目录");
                        if (writer.DeletesSkipped > 0)
                            _log("⚠️ " + writer.LastError);
                    }
                }

                try { state.Save(force: true); } catch { }
                if (state.SkippedHits > 0)
                    _log("增量同步命中: " + state.SkippedHits + " 个条目直接跳过详情请求");

                var written = Math.Max(0, summary.Items - summary.Skipped);
                summary.Detail = $"站点 {summary.Sites}（失败 {summary.SitesFailed}），条目 {summary.Items}"
                               + $"（写入 {written}，跳过 {summary.Skipped}），分集 {summary.Episodes}，"
                               + (summary.Unchanged > 0 ? $"（其中 {summary.Unchanged} 个内容未变）" : "")
                               + $"清理 {summary.Removed}，错误 {summary.Errors}";
                if (summary.Items > 0 && written == 0 && summary.Unchanged == 0)
                    summary.Detail += "  ⚠️ 全部条目都没写进磁盘"
                                    + (string.IsNullOrEmpty(_lastWriteError)
                                        ? "（检查输出目录是否存在且 Emby 用户可写）"
                                        : "：" + _lastWriteError);
                Finish(summary, cfg, saveConfig);
                return true;
            }
            catch (OperationCanceledException)
            {
                _log("同步已停止");
                summary.Detail = $"已手动停止（站点 {Progress.SiteIndex}/{Progress.SiteTotal}，条目 {summary.Items}，分集 {summary.Episodes}）";
                Finish(summary, cfg, saveConfig);
                return true;
            }
            finally
            {
                try { _state?.Save(force: true); } catch { }   // 取消/异常路径也要落盘
                Update(p => { p.Running = false; p.FinishedUtc = DateTime.UtcNow; });
                try { _cts?.Dispose(); } catch { }
                _cts = null;
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
        /// <summary>
        /// 问 spider-hub 哪些站点是网盘类。hub 在 JVM 里能沿继承链判断
        /// （catvod 的网盘实现都继承 com.github.catvod.spider.Pan），比按名字猜准。
        /// hub 连不上就返回空集合，退回 LooksLikeNetdisk 兜底。
        /// </summary>
        public static async Task<HashSet<string>> FetchNetdiskKeysAsync(
            string hubUrl, HttpClient http, CancellationToken ct)
        {
            var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            if (string.IsNullOrWhiteSpace(hubUrl)) return set;
            try
            {
                var url = hubUrl.Trim().TrimEnd('/') + "/api/sites?netdisk=1";
                using (var resp = await http.GetAsync(url, HttpCompletionOption.ResponseContentRead, ct).ConfigureAwait(false))
                {
                    if (!resp.IsSuccessStatusCode) return set;
                    var json = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
                    using (var doc = System.Text.Json.JsonDocument.Parse(json))
                    {
                        if (!doc.RootElement.TryGetProperty("sites", out var arr)) return set;
                        foreach (var el in arr.EnumerateArray())
                        {
                            bool nd = el.TryGetProperty("netdisk", out var ndEl) && ndEl.ValueKind == System.Text.Json.JsonValueKind.True;
                            if (!nd) continue;
                            if (el.TryGetProperty("key", out var kEl) && kEl.ValueKind == System.Text.Json.JsonValueKind.String)
                            {
                                var k = kEl.GetString();
                                if (!string.IsNullOrWhiteSpace(k)) set.Add(k);
                            }
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                _logStatic?.Invoke("[netdisk] 取 hub 网盘名单失败（改用名字兜底）: " + ex.Message);
            }
            return set;
        }

        private static Action<string> _logStatic;

        /// <summary>
        /// 问 spider-hub 用户勾选了哪些站点（站点页的两个表格就是这份数据）。
        /// 这正是"每次同步都是固定 3 个站"的解法：名单由页面决定，不由配置写死。
        /// 返回 null 表示 hub 不可达，调用方退回本地白名单。
        /// </summary>
        public static async Task<HashSet<string>> FetchSyncSitesAsync(
            string hubUrl, HttpClient http, CancellationToken ct)
        {
            if (string.IsNullOrWhiteSpace(hubUrl)) return null;
            try
            {
                var url = hubUrl.Trim().TrimEnd('/') + "/api/sync-sites";
                using (var resp = await http.GetAsync(url, HttpCompletionOption.ResponseContentRead, ct).ConfigureAwait(false))
                {
                    if (!resp.IsSuccessStatusCode) return null;
                    var json = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
                    using (var doc = System.Text.Json.JsonDocument.Parse(json))
                    {
                        if (!doc.RootElement.TryGetProperty("sites", out var arr)
                            || arr.ValueKind != System.Text.Json.JsonValueKind.Array)
                            return null;
                        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                        foreach (var el in arr.EnumerateArray())
                            if (el.ValueKind == System.Text.Json.JsonValueKind.String)
                                set.Add(el.GetString());
                        return set;
                    }
                }
            }
            catch (Exception ex)
            {
                _logStatic?.Invoke("[sync] 取站点页勾选失败（退回本地白名单）: " + ex.Message);
                return null;
            }
        }

        /// <summary>拿不到 hub 名单时的兜底判定（同名规则也在 hub 侧用）。</summary>
        public static bool LooksLikeNetdisk(string api, string name)
        {
            var a = api ?? "";
            var n = name ?? "";
            if (a.StartsWith("csp_Pan", StringComparison.OrdinalIgnoreCase)) return true;
            foreach (var k in new[]{"网盘", "云盘", "盘搜", "搜盘", "夸克", "115", "迅雷",
                                     "百度盘", "天翼", "阿里云", "UC网盘", "123云盘", "光鸭", "分享"})
                if (n.IndexOf(k, StringComparison.OrdinalIgnoreCase) >= 0) return true;
            return false;
        }

        /// <summary>
        /// 分类为空时的降级同步：直接按 ac=videolist&pg=1..N 拉列表。
        /// 实测很多源（尤其接 spider-hub 的）class 数组是空的，但列表本身是好的，
        /// 以前这里直接跳过整站 → 同步"成功"但磁盘 0 个文件。
        /// </summary>
        private async Task<int> SyncSiteWithoutCategoriesAsync(
            CmsClient client, TvBoxSite site, PluginConfiguration cfg,
            StrmWriter writer, HashSet<string> keep, CancellationToken ct, SyncSummary summary)
        {
            int written = 0, seen = 0;
            int maxPages = Math.Max(1, cfg.MaxPagesPerCategory);
            var name = site.DisplayName ?? site.Name;

            for (int page = 1; page <= maxPages; page++)
            {
                ct.ThrowIfCancellationRequested();
                PagedResult res;
                try
                {
                    res = await client.GetListAsync(site, null, page, ct).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                catch (Exception ex)
                {
                    _log("     第 " + page + " 页失败: " + ex.Message);
                    summary.Errors++;
                    break;
                }
                if (res?.Items == null || res.Items.Count == 0) break;

                foreach (var item in res.Items)
                {
                    ct.ThrowIfCancellationRequested();
                    if (cfg.MaxItemsPerRun > 0 && summary.Items >= cfg.MaxItemsPerRun) break;
                    if (string.IsNullOrWhiteSpace(item.Id)) continue;
                    seen++;
                    // 增量：列表侧指纹没变且还在有效期内 → 详情都不拉
                    if (TrySkipByIncremental(cfg, site, item, keep, summary)) continue;
                    try
                    {
                        var details = await client.GetDetailAsync(site, new[] { item.Id }, ct).ConfigureAwait(false);
                        var detail = details != null && details.Count > 0 ? details[0] : null;
                        if (detail == null) { summary.Skipped++; Update(p => p.ItemsSkipped++); continue; }

                        var line = detail.Lines != null && detail.Lines.Count > 0 ? detail.Lines[0] : null;
                        if (line?.Episodes == null || line.Episodes.Count == 0)
                        {
                            summary.Skipped++; Update(p => p.ItemsSkipped++);
                            continue;
                        }

                        if (line.Episodes.Count > 1)
                        {
                            if (cfg.VerifyPlayUrl)
                            {
                                // 逐集校验：一个源里经常是"前几集能播、后面几集全是图片广告"，
                                // 只验首集会放过大量死地址（实测 40 个样本里就有 1 个是 17 集坏了）。
                                int dead = 0;
                                var live = await FilterPlayableEpisodesAsync(
                                    client, line.Episodes, ct, _ => dead++).ConfigureAwait(false);
                                if (live.Count == 0)
                                {
                                    summary.Skipped++; Update(p => p.ItemsSkipped++);
                                    _log("     [" + (item.Name ?? item.Id) + "] 跳过：" + line.Episodes.Count + " 集全部不可用");
                                    if (cfg.RequestDelayMs > 0) await Task.Delay(cfg.RequestDelayMs, ct).ConfigureAwait(false);
                                    continue;
                                }
                                if (dead > 0)
                                    _log("     [" + (item.Name ?? item.Id) + "] " + line.Episodes.Count + " 集里有 " + dead + " 集不可用，只写能播的 " + live.Count + " 集");
                                line = new VodLine { Name = line.Name, Episodes = live };
                            }
                            var outs = writer.WriteSeries(name, detail, line);
                            foreach (var o in outs) if (!string.IsNullOrEmpty(o.Path)) keep.Add(o.Path);
                            RecordIncremental(site, item, detail, outs);
                            int added = outs.Count(o => o.Created || o.Updated);
                            summary.Episodes += added;
                            summary.Unchanged += outs.Count(o => !string.IsNullOrEmpty(o.Path) && !o.Created && !o.Updated);
                            summary.Items++;
                            Update(p => { p.ItemsWritten++; p.EpisodesWritten += added; });
                            if (added > 0) written++;
                        }
                        else
                        {
                            var epUrl = line.Episodes[0]?.Url;
                            if (cfg.VerifyPlayUrl)
                            {
                                var (pok, why) = await client.VerifyPlayableAsync(epUrl, ct).ConfigureAwait(false);
                                if (!pok)
                                {
                                    summary.Skipped++; Update(p => p.ItemsSkipped++);
                                    _log("     [" + (item.Name ?? item.Id) + "] 跳过：播放地址不可用（" + why + "）");
                                    if (cfg.RequestDelayMs > 0) await Task.Delay(cfg.RequestDelayMs, ct).ConfigureAwait(false);
                                    continue;
                                }
                            }
                            var o = writer.WriteMovie(name, detail, line);
                            if (!string.IsNullOrEmpty(o.Path)) keep.Add(o.Path);
                            RecordIncremental(site, item, detail, new[] { o });
                            if (o.Created || o.Updated)
                            {
                                summary.Items++;
                                Update(p => { p.ItemsWritten++; p.EpisodesWritten++; });
                                written++;
                            }
                            else { summary.Skipped++; Update(p => p.ItemsSkipped++); }
                        }
                        if (!string.IsNullOrEmpty(writer.LastError)) _lastWriteError = writer.LastError;
                    }
                    catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                    catch (Exception ex)
                    {
                        summary.Errors++;
                        _log("     [" + (item.Name ?? item.Id) + "] 失败: " + ex.Message);
                    }
                    if (cfg.RequestDelayMs > 0) await Task.Delay(cfg.RequestDelayMs, ct).ConfigureAwait(false);
                }
                if (cfg.MaxItemsPerRun > 0 && summary.Items >= cfg.MaxItemsPerRun) break;
                if (res.Items.Count < 20) break;
            }
            _log("     没分类但直接拉到 " + seen + " 条，写入 " + written + " 条");
            return written;
        }

        public static IEnumerable<TvBoxSite> SelectSites(IEnumerable<TvBoxSite> all, PluginConfiguration cfg)
            => SelectSites(all, cfg, null);

        public static IEnumerable<TvBoxSite> SelectSites(IEnumerable<TvBoxSite> all, PluginConfiguration cfg,
                                                         HashSet<string> netdiskKeys)
            => SelectSites(all, cfg, netdiskKeys, null);

        public static IEnumerable<TvBoxSite> SelectSites(IEnumerable<TvBoxSite> all, PluginConfiguration cfg,
                                                         HashSet<string> netdiskKeys, HashSet<string> pickedSites)
        {
            var white = SplitCsv(cfg.SiteWhitelist);
            var black = SplitCsv(cfg.SiteBlacklist);
            var nameFilters = SplitCsv(cfg.SiteNameFilter);
            var dead = new HashSet<string>(cfg.DeadSites ?? new List<string>(), StringComparer.OrdinalIgnoreCase);

            foreach (var s in all)
            {
                if (s == null || !s.Supported) continue;
                if (cfg.ExcludeCloudDrive)
                {
                    bool nd = (netdiskKeys != null && netdiskKeys.Contains(s.Key ?? ""))
                              || LooksLikeNetdisk(s.Api, s.DisplayName ?? s.Name);
                    if (nd) continue;      // 网盘类：没有账号拿不到直链，解析阶段就排除
                }
                if (dead.Contains(s.Key ?? "")) continue;
                if (black.Count > 0 && Contains(black, s.Key)) continue;
                // 站点页勾选优先：勾了就同步勾的，没勾过才回退到本地白名单
                if (pickedSites != null && pickedSites.Count > 0)
                {
                    if (!pickedSites.Contains(s.Key ?? "")) continue;
                }
                else if (white.Count > 0 && !Contains(white, s.Key)) continue;
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

        // ───────────────────── 详情批量拉取（超时自动拆半）─────────────────────

        /// <summary>
        /// 批量拉详情，失败就对半拆开重试。
        ///
        /// 实测（生产 47 站）：一批 20 个 id 里只要有一个 spider 卡住，整批 20 条全丢，
        /// 日志就是反复刷「拉详情失败 (20 个): HttpClient.Timeout of 20 seconds elapsing」。
        /// 拆开之后卡住的只剩最后那 1~2 条，其余照常入库。
        /// </summary>
        private async Task<List<VodItem>> GetDetailsResilientAsync(
            CmsClient client, TvBoxSite site, List<string> ids, PluginConfiguration cfg, CancellationToken ct)
        {
            var all = new List<VodItem>();
            int size = Math.Max(1, cfg.DetailBatchSize);
            foreach (var batch in Chunk(ids, size))
            {
                ct.ThrowIfCancellationRequested();
                all.AddRange(await GetDetailBatchAsync(client, site, batch.ToList(), cfg, ct, 0).ConfigureAwait(false));
            }
            return all;
        }

        private async Task<List<VodItem>> GetDetailBatchAsync(
            CmsClient client, TvBoxSite site, List<string> batch, PluginConfiguration cfg,
            CancellationToken ct, int depth)
        {
            try
            {
                var r = await client.GetDetailAsync(site, batch, ct).ConfigureAwait(false);
                return r ?? new List<VodItem>();
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception ex)
            {
                if (batch.Count <= 1 || depth >= 5)
                {
                    _log($"     拉详情失败 ({batch.Count} 个): {ShortErr(ex)}");
                    return new List<VodItem>();
                }
                int mid = batch.Count / 2;
                _log($"     拉详情失败 ({batch.Count} 个)，拆成 {mid}+{batch.Count - mid} 重试: {ShortErr(ex)}");
                var left = await GetDetailBatchAsync(client, site, batch.Take(mid).ToList(), cfg, ct, depth + 1)
                                 .ConfigureAwait(false);
                await DelayAsync(cfg, ct).ConfigureAwait(false);
                var right = await GetDetailBatchAsync(client, site, batch.Skip(mid).ToList(), cfg, ct, depth + 1)
                                  .ConfigureAwait(false);
                return left.Concat(right).ToList();
            }
        }

        private static string ShortErr(Exception ex)
        {
            var m = ex?.Message ?? "";
            if (m.Contains("Timeout", StringComparison.OrdinalIgnoreCase)) return "等待超时";
            return m.Length > 90 ? m.Substring(0, 90) : m;
        }

        // ───────────────────── 增量 / 元数据 / 汇报 小工具 ─────────────────────

        /// <summary>增量命中：把上次写出的文件并进 keep 并计数，返回 true 表示可以跳过详情请求。</summary>
        private bool TrySkipByIncremental(PluginConfiguration cfg, TvBoxSite site, VodItem listed,
                                          HashSet<string> keep, SyncSummary summary)
        {
            if (_state == null || !cfg.IncrementalSync || listed == null || string.IsNullOrEmpty(listed.Id))
                return false;
            if (!_state.TrySkip(site.Key, listed.Id, SyncState.Signature(listed), cfg.IncrementalHours, out var cached))
                return false;
            foreach (var cp in cached) keep.Add(cp);
            summary.Items++;
            summary.Unchanged++;
            Update(pp => pp.ItemsWritten++);
            return true;
        }

        /// <summary>写完记录增量状态（列表侧指纹 + 实际写出的路径）。</summary>
        private void RecordIncremental(TvBoxSite site, VodItem listed, VodItem detail, IEnumerable<WriteOutcome> outs)
        {
            if (_state == null) return;
            var baseItem = listed ?? detail;
            if (baseItem == null || string.IsNullOrEmpty(baseItem.Id)) return;
            var paths = (outs ?? Enumerable.Empty<WriteOutcome>())
                        .Where(o => !string.IsNullOrEmpty(o.Path)).Select(o => o.Path);
            _state.Record(site.Key, baseItem.Id, SyncState.Signature(baseItem), paths);
        }

        /// <summary>海报下载（同步调用，带大小上限，失败返回 null）。</summary>
        private static byte[] FetchImageBytes(HttpClient http, string url, int maxBytes)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(url)) return null;
                using (var resp = http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead)
                                    .GetAwaiter().GetResult())
                {
                    if (!resp.IsSuccessStatusCode) return null;
                    var len = resp.Content.Headers.ContentLength;
                    if (maxBytes > 0 && len.HasValue && len.Value > maxBytes) return null;
                    using (var st = resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
                    using (var ms = new MemoryStream())
                    {
                        var buf = new byte[64 * 1024];
                        int n;
                        while ((n = st.Read(buf, 0, buf.Length)) > 0)
                        {
                            ms.Write(buf, 0, n);
                            if (maxBytes > 0 && ms.Length > maxBytes) return null;
                        }
                        return ms.ToArray();
                    }
                }
            }
            catch { return null; }
        }

        /// <summary>把站点成功/失败汇报给 hub（hub 侧据此做死站退避：连续失败自动移出同步）。</summary>
        private async Task ReportSiteAsync(PluginConfiguration cfg, string key, bool ok, CancellationToken ct)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(cfg?.SpiderHubUrl) || string.IsNullOrWhiteSpace(key)) return;
                var url = cfg.SpiderHubUrl.TrimEnd('/') + (ok ? "/api/sites/ok" : "/api/sites/fail");
                using (var http = new HttpClient { Timeout = TimeSpan.FromSeconds(6) })
                using (var content = new StringContent(
                    System.Text.Json.JsonSerializer.Serialize(new { key }),
                    System.Text.Encoding.UTF8, "application/json"))
                {
                    await http.PostAsync(url, content, ct).ConfigureAwait(false);
                }
            }
            catch { /* 汇报失败绝不能影响同步 */ }
        }

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
                    // 很多源（尤其接 spider-hub 的）class 数组是空的，但 ac=videolist&pg=1
                    // 直接就能返回条目。以前这里直接跳过整站，导致"同步成功但 0 文件"。
                    // 现在降级成「不按分类，直接拉前 N 页」。
                    _log("     没有分类，改为直接拉 videolist 前 " + Math.Max(1, cfg.MaxPagesPerCategory) + " 页");
                    return await SyncSiteWithoutCategoriesAsync(client, site, cfg, writer, keep, ct, summary)
                                 .ConfigureAwait(false);
                }

                int written = 0;
                int maxPages = Math.Max(1, cfg.MaxPagesPerCategory);

                foreach (var cat in cats)
                {
                    ct.ThrowIfCancellationRequested();

                    var ids = new List<string>();
                    var listedById = new Dictionary<string, VodItem>(StringComparer.Ordinal);
                    for (int page = 1; page <= maxPages; page++)
                    {
                        PagedResult pr;
                        try
                        {
                            pr = await client.GetListAsync(site, cat.Id, page, ct).ConfigureAwait(false);
                        }
                        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                        catch (Exception ex)
                        {
                            _log($"     分类 {cat.Name} 第 {page} 页失败: {ex.Message}");
                            summary.Errors++;
                            break;
                        }

                        foreach (var it in pr.Items)
                            if (!string.IsNullOrEmpty(it.Id))
                            {
                                ids.Add(it.Id);
                                listedById[it.Id] = it;      // 增量判据要用列表侧的名称/备注/年份
                            }

                        if (pr.Items.Count == 0) break;
                        if (pr.PageCount > 0 && page >= pr.PageCount) break;
                        await DelayAsync(cfg, ct).ConfigureAwait(false);
                    }

                    if (ids.Count == 0) continue;

                    // 去重后先做增量过滤，再分批拉详情（ac=detail 支持多 id 逗号分隔）
                    var distinct = ids.Distinct()
                        .Where(id => !TrySkipByIncremental(cfg, site,
                                        listedById.TryGetValue(id, out var li) ? li : null, keep, summary))
                        .ToList();
                    if (distinct.Count == 0) continue;
                    foreach (var batch in Chunk(distinct, Math.Max(1, cfg.DetailBatchSize)))
                    {
                        ct.ThrowIfCancellationRequested();
                        List<VodItem> details;
                        try
                        {
                            // 整批超时就对半拆开重试，别让一个卡住的 spider 带走其他 19 条
                            details = await GetDetailBatchAsync(client, site, batch.ToList(), cfg, ct, 0)
                                            .ConfigureAwait(false);
                        }
                        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                        catch (Exception ex)
                        {
                            _log($"     拉详情失败 ({batch.Count()} 个): {ShortErr(ex)}");
                            summary.Errors++;
                            continue;
                        }
                        if (details == null || details.Count == 0)
                        {
                            summary.Errors++;
                            continue;
                        }

                        foreach (var item in details)
                        {
                            var line = PlayUrlParser.PickBestLine(item);
                            if (line == null) continue;

                            if (item.IsSeries)
                            {
                                // 逐集校验（这条才是"有分类"的主路径，之前只校验了无分类降级路径）
                                if (cfg.VerifyPlayUrl)
                                {
                                    int dead = 0;
                                    var live = await FilterPlayableEpisodesAsync(
                                        client, line.Episodes, ct, _ => dead++).ConfigureAwait(false);
                                    if (live.Count == 0)
                                    {
                                        summary.Items++;
                                        summary.Skipped++;
                                        Update(p => p.ItemsSkipped++);
                                        _log("     [" + (item.Name ?? item.Id) + "] 跳过：" + line.Episodes.Count + " 集全部不可用");
                                        continue;
                                    }
                                    if (dead > 0)
                                        _log("     [" + (item.Name ?? item.Id) + "] " + line.Episodes.Count
                                             + " 集里 " + dead + " 集不可用，只写能播的 " + live.Count + " 集");
                                    line = new VodLine { Name = line.Name, Episodes = live };
                                }
                                var outs = writer.WriteSeries(site.DisplayName, item, line);
                                listedById.TryGetValue(item.Id ?? "", out var listedItem);
                                RecordIncremental(site, listedItem, item, outs);
                                var added = outs.Count(o => o.Created || o.Updated);
                                var same = outs.Count(o => !string.IsNullOrEmpty(o.Path) && !o.Created && !o.Updated);
                                foreach (var o in outs)
                                    if (!string.IsNullOrEmpty(o.Path)) keep.Add(o.Path);
                                if (added + same > 0)   // 内容没变也是成功：文件在磁盘上
                                {
                                    written++;
                                    summary.Items++;
                                    summary.Episodes += added;
                                    summary.Unchanged += same;
                                    Update(p => { p.ItemsWritten++; p.EpisodesWritten += added; });
                                }
                                else
                                {
                                    summary.Items++;
                                    { summary.Skipped++; Update(p => p.ItemsSkipped++); if (!string.IsNullOrEmpty(writer.LastError)) _lastWriteError = writer.LastError; }
                                }
                            }
                            else
                            {
                                // 电影分支也要校验（之前只有剧集分支校验，死链电影照样写出来）
                                if (cfg.VerifyPlayUrl)
                                {
                                    var mvUrl = line.Episodes[0]?.Url;
                                    var (mok, mwhy) = await client.VerifyPlayableAsync(mvUrl, ct).ConfigureAwait(false);
                                    if (!mok)
                                    {
                                        summary.Items++;
                                        summary.Skipped++;
                                        Update(p => p.ItemsSkipped++);
                                        _log("     [" + (item.Name ?? item.Id) + "] 跳过：播放地址不可用（" + mwhy + "）");
                                        if (cfg.RequestDelayMs > 0) await Task.Delay(cfg.RequestDelayMs, ct).ConfigureAwait(false);
                                        continue;
                                    }
                                }
                                var o = writer.WriteMovie(site.DisplayName, item, line);
                                listedById.TryGetValue(item.Id ?? "", out var listedMovie);
                                RecordIncremental(site, listedMovie, item, new[] { o });
                                if (!string.IsNullOrEmpty(o.Path)) keep.Add(o.Path);
                                if (!string.IsNullOrEmpty(o.Path))   // 存在即可（含内容未变）
                                {
                                    written++;
                                    summary.Items++;
                                    summary.Episodes++;
                                    Update(p => { p.ItemsWritten++; p.EpisodesWritten++; });
                                }
                                else
                                {
                                    summary.Items++;
                                    { summary.Skipped++; Update(p => p.ItemsSkipped++); if (!string.IsNullOrEmpty(writer.LastError)) _lastWriteError = writer.LastError; }
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
