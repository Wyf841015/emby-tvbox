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
        /// <summary>本轮是否被 MaxItemsPerRun / MaxPagesPerCategory 之类的上限截断。</summary>
        public bool Truncated { get; set; }
        public string Detail { get; set; } = "";
    }

    /// <summary>
    /// 同步编排：拉配置 → 逐站点拉分类/列表/详情 → 写 strm → 清理孤儿 → 通知 Emby 刷新。
    /// 单一实例运行（_gate），避免定时任务和手动触发撞车。
    /// </summary>
    public class SyncService
    {
        private readonly Action<string> _log;

        /// <summary>Emby 的局域网访问地址：私有分集 id 改写成 /TvBox/Play 时用。</summary>
        private string _embyBase = string.Empty;
        /// <summary>本轮输出根目录（换源写文件时要用，而它在被调函数里拿不到参数）。</summary>
        private string _outputRoot = string.Empty;
        /// <summary>strm 播放地址策略：proxy / direct / direct_fallback（direct* = 写源站直链，外网可播）。</summary>
        private string _playMode = "proxy";
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

        /// <summary>
        /// 停止请求标志。
        ///
        /// 为什么不用 CancellationToken 就够了：spider 调用是同步阻塞的（单次最长 45 秒），
        /// 而且取消令牌一旦取消过就再也不会"重新生效"，配合「只有零星几处检查」的循环，
        /// 表现就是「点了停止、进度还在涨」。这里改成独立标志，所有循环（站点 / 条目 /
        /// 逐集校验 / 拆批重试 / 线路兜底）都检查它，且可以重复点。
        /// </summary>
        private static volatile bool _stopRequested;

        public bool StopRequested { get { return _stopRequested; } }

        /// <summary>请求停止当前同步。幂等：重复点也生效，不会因为"令牌早就取消过"而返回失败。</summary>
        public bool RequestStop()
        {
            _stopRequested = true;
            var cts = _cts;
            if (cts != null)
            {
                try { if (!cts.IsCancellationRequested) cts.Cancel(); } catch { }
            }
            _log(cts == null
                ? "收到停止请求（当前没有活动同步，已记录标志）"
                : "收到停止请求，正在中止（每个检查点都会立即退出）");
            return true;
        }

        /// <summary>所有循环统一用这个：先看停止标志，再看取消令牌。</summary>
        private static void ThrowIfStopped(CancellationToken ct)
        {
            if (_stopRequested) throw new OperationCanceledException("用户请求停止同步");
            ct.ThrowIfCancellationRequested();
        }

        /// <summary>
        /// 单条推送：把某个站点的一条内容（电影或剧）立刻写进 Emby 库。
        ///
        /// 走跟同步完全一样的三步：拉详情 → 线路选择 + 私有 id 解析（playerContent）→
        /// 逐集校验 → 写 strm/nfo/海报。搜索页「一键推送」就是调它，
        /// 所以推出来的东西跟同步出来的文件名、目录结构完全一致。
        /// </summary>
        public async Task<Dictionary<string, object>> PushOneAsync(
            PluginConfiguration cfg, string siteKey, string siteNameForDir, string itemId, string outputRoot,
            string embyBaseForProxy, CancellationToken ct)
        {
            var res = new Dictionary<string, object>();
            if (string.IsNullOrWhiteSpace(siteKey) || string.IsNullOrWhiteSpace(itemId))
            {
                res["ok"] = false; res["msg"] = "缺少站点或条目 id"; return res;
            }
            var hub = (cfg?.SpiderHubUrl ?? string.Empty).Trim().TrimEnd('/');
            if (hub.Length == 0) { res["ok"] = false; res["msg"] = "插件里没配 hub 地址"; return res; }

            // 关键：私有分集 id 要改写成 /TvBox/Play 地址，必须知道 Emby 的局域网地址；
            // 直链模式下则改成源站真实地址（外网可播）
            SetupPlayMode(cfg, embyBaseForProxy);
            // 单条推送也要用同一套签名密钥，否则推出来的地址播不了
            if (string.IsNullOrWhiteSpace(cfg.StreamSign))
            {
                var lazySign = Plugin.Instance?.EnsureStreamSign();
                if (!string.IsNullOrWhiteSpace(lazySign)) cfg.StreamSign = lazySign;
            }
            TvBox.Plugin.Api.TvBoxApi.SetStreamSign(cfg.StreamSign, cfg.StreamSignRequired);
            Core.PlayLinkCache.TtlMinutes = Math.Max(0, cfg.PlayCacheMinutes);


            using var http = CreateClient(cfg);
            var client = new CmsClient(http, m => _log("     [push] " + m), cfg.Referer);
            // 站点对象直接指向 hub 的 MacCMS 适配层，不用再读一遍配置
            // 站点目录名：优先用站点显示名（跟同步侧一致），没有才退回 key。
            // 之前这里错把「片名」当站点名传下去，结果文件被写进 <输出目录>/<片名>/movies/…
            var dirSite = string.IsNullOrWhiteSpace(siteNameForDir) ? siteKey : siteNameForDir.Trim();
            var site = new TvBoxSite
            {
                Key = siteKey,
                Name = dirSite,
                Api = hub + "/v/" + Uri.EscapeDataString(siteKey) + "/api.php/provide/vod/",
            };

            var details = await client.GetDetailAsync(site, new[] { itemId }, ct).ConfigureAwait(false);
            var detail = details != null && details.Count > 0 ? details[0] : null;
            if (detail == null) { res["ok"] = false; res["msg"] = "拉不到详情（站点可能已失效）"; return res; }

            var line = PlayUrlParser.PickBestLine(detail);
            if (line?.Episodes == null || line.Episodes.Count == 0)
            { res["ok"] = false; res["msg"] = "这条没有播放地址"; return res; }

            line = ResolvePrivateIds(line, site, cfg, detail?.Id);

            List<VodEpisode> live = line.Episodes;
            bool series = line.Episodes.Count > 1;
            if (cfg.VerifyPlayUrl)
            {
                live = await FilterPlayableEpisodesAsync(client, line.Episodes, site, cfg, ct,
                    _ => { }, _ => { }).ConfigureAwait(false);
                if (live.Count == 0 && detail.Lines != null && detail.Lines.Count > 1)
                {
                    foreach (var cand in detail.Lines.Skip(1).Take(3))
                    {
                        ThrowIfStopped(ct);
                        if (cand?.Episodes == null || cand.Episodes.Count == 0) continue;
                        var candLine = ResolvePrivateIds(new VodLine { Name = cand.Name, Episodes = cand.Episodes }, site, cfg, detail?.Id);
                        var live2 = await FilterPlayableEpisodesAsync(client, candLine.Episodes, site, cfg, ct,
                            _ => { }, _ => { }).ConfigureAwait(false);
                        if (live2.Count > 0) { line = candLine; live = live2; break; }
                    }
                }
                if (live.Count == 0)
                { res["ok"] = false; res["msg"] = line.Episodes.Count + " 集全部不可播"; return res; }
                series = live.Count > 1;
            }

            line = new VodLine { Name = line.Name, Episodes = live };

            _outputRoot = outputRoot;
            var writer = new StrmWriter(outputRoot, m => _log("[push] " + m));
            writer.WriteNfo = cfg.WriteNfo;
            writer.DownloadPoster = cfg.DownloadPoster;
            writer.PosterMaxBytes = Math.Max(0, cfg.PosterMaxMb) * 1024 * 1024;
            writer.MaxDeletePerRun = 0;                       // 单条推送绝不做清理
            writer.NameTemplate = cfg.NameTemplate;
            writer.RenameRules = ParseLineList(cfg.RenameRules);
            writer.SkipKeywords = SplitCsv(cfg.SkipKeywords);
            writer.DownloadSubtitle = cfg.DownloadSubtitle;
            AttachTmdb(writer, cfg);
            if (cfg.DownloadPoster)
            {
                var imgHttp = new HttpClient { Timeout = TimeSpan.FromSeconds(Math.Max(5, cfg.TimeoutSeconds)) };
                try
                {
                    if (!string.IsNullOrWhiteSpace(cfg.UserAgent))
                        imgHttp.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", cfg.UserAgent);
                }
                catch { }
                writer.FetchImage = url => FetchImageBytes(imgHttp, url, writer.PosterMaxBytes);
            }

            var name = string.IsNullOrWhiteSpace(detail.Name) ? (detail.Id ?? itemId) : detail.Name;
            var paths = new List<string>();
            if (series)
            {
                foreach (var o in writer.WriteSeries(dirSite, detail, line))
                    if (!string.IsNullOrEmpty(o.Path)) paths.Add(o.Path);
            }
            else
            {
                var o = writer.WriteMovie(dirSite, detail, line);
                if (!string.IsNullOrEmpty(o.Path)) paths.Add(o.Path);
            }

            res["ok"] = paths.Count > 0;
            res["series"] = series;
            res["count"] = paths.Count;
            res["paths"] = paths;
            res["name"] = name;
            if (paths.Count == 0) res["msg"] = "没写出文件（检查输出目录权限）";
            _log("[push] " + siteKey + " " + itemId + " → " + paths.Count + " 个文件"
                 + (paths.Count > 0 ? "，首个: " + paths[0] : ""));
            return res;
        }

        private void Update(Action<SyncProgress> mutate)
        {
            lock (_stateLock) mutate(Progress);
        }


        /// <summary>把 TMDB 查询钩子挂到 writer 上（没配 Key 就什么都不做）。</summary>
        private void AttachTmdb(StrmWriter writer, PluginConfiguration cfg)
        {
            if (writer == null) return;
            if (string.IsNullOrWhiteSpace(cfg.TmdbApiKey)) return;
            var key = cfg.TmdbApiKey.Trim();
            var lang = cfg.TmdbLanguage;
            writer.TmdbLookup = (item, isSeries) =>
            {
                var q = StrmWriter.CleanTitle(item?.Name ?? string.Empty);
                if (string.IsNullOrWhiteSpace(q)) return null;
                return TmdbClient.Search(key, lang, q, item?.Year, isSeries, m => _log(m));
            };
            _log("TMDB 规范化: 开（语言 " + (string.IsNullOrWhiteSpace(lang) ? "zh-CN" : lang) + "，只改 nfo 元数据不改目录名）");
        }

        /// <summary>按行切分成列表（用于 RenameRules / SyncPlans 这类多行配置）。</summary>
        internal static List<string> ParseLineList(string text)
        {
            var list = new List<string>();
            if (string.IsNullOrWhiteSpace(text)) return list;
            foreach (var raw in text.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n'))
            {
                var t = raw.Trim();
                if (t.Length > 0 && !t.StartsWith("#", StringComparison.Ordinal)) list.Add(t);
            }
            return list;
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
        ///
        /// 两条现实约束决定了这里不能无脑全量校验：
        ///   1. 源里动辄 200+ 集（实测 265/288/247/221 集），每集要 2 个请求，
        ///      一个条目就能烧掉 500+ 次请求、耗掉一两分钟——这是同步慢的头号原因；
        ///   2. 但只验首集又会放过"前几集正常、后面全是图片广告"的源（实测有 17 集坏的）。
        /// 折中：超过 VerifyMaxEpisodes 就做样本校验（首 12 + 尾 12 + 中间均匀抽），
        /// 没被抽到的集按"可播"处理直接写入，并在日志里说明抽了多少、为什么。
        ///
        /// 顺带处理第三类地址：不是媒体流的（解析接口 ?url=…、网页播放页、302 中转）
        /// 先交给 PlayUrlResolver 解一次，解出来的真地址同时回写到分集上，
        /// 这样写进 strm 的就是能播的那个。
        /// </summary>
        /// <summary>
        /// 把 spider 的私有分集 id 换成 hub 的 /play/ 地址（hub 侧走 Spider.playerContent 解析）。
        ///
        /// 有些源 vod_play_url 里是 `146660@1314815`、`CO4K_…@CO4K@重生@1`、`/play/50375-1-1.html`
        /// 这类私有格式，以前要么被 PlayUrlParser 丢掉整站为空，要么被当成地址写进 strm 播不了。
        /// </summary>
        /// <summary>
        /// 决定这一轮 strm 里写什么地址。
        ///
        /// 外网播放的关键就在这：内网 IP（192.168.x.x）外网播放器连不上，
        /// 而走 fnOS 网关的域名又要登录态，播放器拿不到 cookie —— 所以给出直链模式：
        /// 直接把源站地址写进 strm，客户端连源站（不经过 NAS）。
        /// </summary>
        /// <summary>proxy 和 redirect 两种模式写进 strm 的地址形态一致（都指向 /TvBox/Play）。</summary>
        private static bool ProxyStyle(string mode) => mode == "proxy" || mode == "redirect";

        private void SetupPlayMode(PluginConfiguration cfg, string embyBaseForProxy)
        {
            var mode = (cfg?.StrmPlayMode ?? "proxy").Trim().ToLowerInvariant();
            if (mode != "direct" && mode != "direct_fallback" && mode != "redirect") mode = "proxy";
            _playMode = mode;

            var explicitBase = (cfg?.StreamBaseUrl ?? string.Empty).Trim();
            _embyBase = (explicitBase.Length > 0 ? explicitBase : (embyBaseForProxy ?? string.Empty)).Trim().TrimEnd('/');

            if (mode == "redirect")
                _log("播放地址策略: 302 重定向（strm 写本机地址，播放时实时解析回 307 源站直链，直链过期不再需要重刷 strm）");
            else if (mode == "proxy")
            {
                if (explicitBase.Length > 0) _log("播放地址基址: " + _embyBase);
            }
            else
            {
                _log("播放地址策略: 直链（写源站地址，外网可直接播放）"
                     + (mode == "direct_fallback" ? "，解析不出的集退回插件代理" : ""));
                if (cfg != null && cfg.DirectRefreshHours > 0)
                    _log("直链每 " + cfg.DirectRefreshHours + " 小时重解析一次（源站 auth_key 会过期）");
            }
        }

        /// <summary>
        /// 把私有分集 id 解析成源站真实地址（直链模式用）。
        /// 先查直链缓存；没有再打 hub 的 /play/{site}?flag=&id=&json=1（内部走 Spider.playerContent）。
        /// </summary>
        private string ResolveRealPlayUrl(TvBoxSite site, string flag, string id, PluginConfiguration cfg,
                                          string itemId = null, string epName = null)
        {
            if (string.IsNullOrEmpty(id)) return null;
            var key = Core.PlayLinkCache.Key(site?.Key, flag, id);
            if (Core.PlayLinkCache.TryGet(key, out var hit) && !string.IsNullOrWhiteSpace(hit.Url)) return hit.Url;

            var hub = (cfg?.SpiderHubUrl ?? string.Empty).Trim().TrimEnd('/');
            if (hub.Length == 0) return null;
            var url = hub + "/play/" + Uri.EscapeDataString(site?.Key ?? string.Empty)
                    + "?flag=" + Uri.EscapeDataString(flag ?? string.Empty)
                    + "&id=" + Uri.EscapeDataString(id) + "&json=1";
            // 带上条目 id + 集名：这条线路解不出来时，hub 会自动换其它线路的同名集再试
            if (!string.IsNullOrWhiteSpace(itemId))
                url += "&auto=1&item=" + Uri.EscapeDataString(itemId)
                     + "&name=" + Uri.EscapeDataString(epName ?? string.Empty);
            try
            {
                var timeout = cfg != null && cfg.TimeoutSeconds > 0 ? cfg.TimeoutSeconds : 45;
                using (var http = new System.Net.Http.HttpClient { Timeout = TimeSpan.FromSeconds(timeout) })
                using (var resp = http.GetAsync(url).GetAwaiter().GetResult())
                {
                    if (!resp.IsSuccessStatusCode) return null;
                    var body = resp.Content.ReadAsStringAsync().GetAwaiter().GetResult();
                    using (var doc = System.Text.Json.JsonDocument.Parse(body))
                    {
                        var root = doc.RootElement;
                        bool ok = root.TryGetProperty("ok", out var okEl)
                                  && okEl.ValueKind == System.Text.Json.JsonValueKind.True;
                        if (!ok) return null;
                        if (!root.TryGetProperty("url", out var uEl)) return null;
                        var real = uEl.GetString();
                        if (string.IsNullOrWhiteSpace(real)) return null;
                        var parse = root.TryGetProperty("parse", out var pEl) ? pEl.GetString() : null;
                        var finalUrl = real.Trim();
                        // 直链要写进 strm 给播放器直连：先自己拉一下。
                        // 拉不动或拿到 HTML（源站报错页/防盗页）就当解析失败，别往库里写死链。
                        if (cfg != null && cfg.DirectProbe && !ProbePlayable(finalUrl, site?.Referer, cfg))
                        {
                            _logOnce("[直链] 探测不通过，改走代理: " + finalUrl);
                            return null;
                        }
                        Core.PlayLinkCache.Set(key, finalUrl, parse);
                        return finalUrl;
                    }
                }
            }
            catch (Exception ex)
            {
                _logOnce("[直链] 解析失败 " + id + ": " + ex.Message);
                return null;
            }
        }

        /// <summary>
        /// 直链探测：Range 只取 1KB。
        /// 判定通过 = 2xx/206 且首字节不是 '<'（HTML 错误页/防盗页一律算死链）。
        /// </summary>
        private bool ProbePlayable(string url, string referer, PluginConfiguration cfg)
        {
            if (string.IsNullOrWhiteSpace(url)) return false;
            var sec = cfg != null && cfg.DirectProbeSeconds > 0 ? cfg.DirectProbeSeconds : 6;
            try
            {
                using (var http = new System.Net.Http.HttpClient { Timeout = TimeSpan.FromSeconds(sec) })
                {
                    var req = new System.Net.Http.HttpRequestMessage(System.Net.Http.HttpMethod.Get, url);
                    req.Headers.TryAddWithoutValidation("Range", "bytes=0-1023");
                    req.Headers.TryAddWithoutValidation("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
                    var refUrl = !string.IsNullOrWhiteSpace(site_referer_or(cfg))
                        ? site_referer_or(cfg) : referer;
                    if (!string.IsNullOrWhiteSpace(refUrl)) req.Headers.TryAddWithoutValidation("Referer", refUrl);
                    using (var resp = http.SendAsync(req, System.Net.Http.HttpCompletionOption.ResponseHeadersRead).GetAwaiter().GetResult())
                    {
                        if (!resp.IsSuccessStatusCode) return false;
                        using (var st = resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
                        {
                            var buf = new byte[16];
                            int n = st.Read(buf, 0, buf.Length);
                            if (n <= 0) return true;      // 空响应也算能连（分片类可能为空）
                            var head = System.Text.Encoding.ASCII.GetString(buf, 0, n).TrimStart();
                            return head.Length == 0 || head[0] != '<';
                        }
                    }
                }
            }
            catch { return false; }
        }

        private string site_referer_or(PluginConfiguration cfg)
        {
            return cfg == null ? null : (string.IsNullOrWhiteSpace(cfg.Referer) ? null : cfg.Referer);
        }

        private VodLine ResolvePrivateIds(VodLine line, TvBoxSite site, PluginConfiguration cfg, string itemId = null)
        {
            if (line?.Episodes == null || line.Episodes.Count == 0) return line;
            // 注意：不能直接写 hub 的 /play/ 地址 —— hub 的 TCP 只监听回环（安全审计结论），
            // 局域网播放器连不上。改成指向 Emby 自己的 /TvBox/Play，由插件内部再问 hub。
            var baseUrl = (_embyBase ?? string.Empty).Trim().TrimEnd('/');
            // 直链模式不需要插件基址（解析成功就不写代理地址）；
            // 但 direct_fallback 的退回分支需要，缺基址时直接按 direct 处理。
            if (baseUrl.Length == 0 && ProxyStyle(_playMode)) return line;

            var head = baseUrl + "/TvBox/Play?site=" + Uri.EscapeDataString(site?.Key ?? string.Empty)
                     + "&flag=" + Uri.EscapeDataString(line.Name ?? string.Empty) + "&id=";
            bool changed = false;

            // ── 直链模式要逐集问 hub（每个 id 一次 playerContent），串行会慢到没法用 ──
            // 先并行解析出「私有 id → 真实地址」，再按原顺序组装，保持分集顺序不乱。
            Dictionary<string, string> realMap = null;
            if (!ProxyStyle(_playMode))
            {
                var need = line.Episodes
                    .Where(e => e != null)
                    .Select(e => (e.Url ?? string.Empty).Trim())
                    .Where(u => u.Length > 0
                                && !u.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
                                && !u.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                    .Distinct()
                    .ToList();
                // url → 集名：换线路重试要按集名匹配，串不上就白搭
                var nameOf = new Dictionary<string, string>(StringComparer.Ordinal);
                foreach (var e in line.Episodes)
                {
                    if (e == null) continue;
                    var uu = (e.Url ?? string.Empty).Trim();
                    if (uu.Length == 0 || nameOf.ContainsKey(uu)) continue;
                    nameOf[uu] = e.Name ?? string.Empty;
                }
                if (need.Count > 0)
                {
                    realMap = new Dictionary<string, string>(need.Count, StringComparer.Ordinal);
                    var done = new System.Collections.Concurrent.ConcurrentDictionary<string, string>(StringComparer.Ordinal);
                    var dop = Math.Max(1, Math.Min(cfg != null && cfg.Concurrency > 0 ? cfg.Concurrency * 2 : 6, 8));
                    System.Threading.Tasks.Parallel.ForEach(need, new System.Threading.Tasks.ParallelOptions { MaxDegreeOfParallelism = dop }, u =>
                    {
                        try
                        {
                            nameOf.TryGetValue(u, out var epName);
                            var real = ResolveRealPlayUrl(site, line.Name, u, cfg, itemId, epName);
                            if (!string.IsNullOrWhiteSpace(real)) done[u] = real;
                        }
                        catch { }
                    });
                    foreach (var kv in done) realMap[kv.Key] = kv.Value;
                    _logOnce("[直链] " + (site?.Name ?? site?.Key) + " 的 " + line.Name + " 线路并发解析 " + need.Count + " 个分集（" + dop + " 并发）");
                }
            }

            var eps = new List<VodEpisode>(line.Episodes.Count);
            foreach (var ep in line.Episodes)
            {
                if (ep == null) continue;
                var u = (ep.Url ?? string.Empty).Trim();
                if (u.Length == 0) continue;
                bool isHttp = u.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
                           || u.StartsWith("https://", StringComparison.OrdinalIgnoreCase);
                if (isHttp) { eps.Add(ep); continue; }

                // ── 直链模式：把私有 id 换成源站真实地址直接写 ──
                if (!ProxyStyle(_playMode))
                {
                    string realUrl = null;
                    realMap?.TryGetValue(u, out realUrl);
                    if (!string.IsNullOrWhiteSpace(realUrl))
                    {
                        changed = true;
                        eps.Add(new VodEpisode { Name = ep.Name, Url = realUrl, Resolved = true });
                        continue;
                    }
                    if (_playMode == "direct")
                    {
                        // 解析不出来就别写死链，整集跳过（日志里能查到）
                        changed = true;
                        _logOnce("[直链] " + (site?.Name ?? site?.Key) + " " + u + " 解析不出真实地址，跳过这一集");
                        continue;
                    }
                    // direct_fallback：解析不出退回插件代理地址（下面继续走 head）
                }

                changed = true;
                var playUrl = head + Uri.EscapeDataString(u);
                var psig = TvBox.Plugin.Api.TvBoxApi.SignPlay(site?.Key, line.Name, u);
                if (psig.Length > 0) playUrl += "&s=" + psig;
                eps.Add(new VodEpisode { Name = ep.Name, Url = playUrl, Resolved = true });
            }
            if (!changed) return line;
            _logOnce("[私有格式] " + (site?.Name ?? site?.Key) + " 的 " + line.Name
                     + " 线路走 playerContent 解析（" + eps.Count + " 集）");
            return new VodLine { Name = line.Name, Episodes = eps };
        }

        private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, byte> _logged
            = new System.Collections.Concurrent.ConcurrentDictionary<string, byte>();
        private void _logOnce(string msg)
        {
            if (_logged.TryAdd(msg, 0)) _log(msg);
        }

        private static async Task<List<VodEpisode>> FilterPlayableEpisodesAsync(
            CmsClient client, List<VodEpisode> episodes, TvBoxSite site, PluginConfiguration cfg,
            CancellationToken ct, Action<string> onDead, Action<string> onNote)
        {
            var result = new List<VodEpisode>();
            if (episodes == null || episodes.Count == 0) return result;

            int cap = cfg.VerifyMaxEpisodes;
            bool sampled = cap > 0 && episodes.Count > cap;
            var verifyIdx = new List<int>();
            if (!sampled)
            {
                for (int i = 0; i < episodes.Count; i++) verifyIdx.Add(i);
            }
            else
            {
                // 首尾各 12，但总配额不能超过上限（有人把上限调到 6 时就只剩首尾 3+3）
                int head = Math.Min(12, Math.Max(1, cap / 2));
                int tail = head;
                int h = Math.Min(head, episodes.Count);
                int t = Math.Min(tail, Math.Max(0, episodes.Count - h));
                for (int i = 0; i < h; i++) verifyIdx.Add(i);
                for (int i = episodes.Count - t; i < episodes.Count; i++) verifyIdx.Add(i);

                int span = episodes.Count - h - t;
                int need = Math.Max(0, cap - verifyIdx.Count);
                if (need > 0 && span > 0)
                {
                    double step = (double)span / need;
                    for (int j = 0; j < need; j++)
                    {
                        int idx = h + (int)Math.Round(j * step + step / 2);
                        if (idx >= h && idx < episodes.Count - t) verifyIdx.Add(idx);
                    }
                }
                verifyIdx = verifyIdx.Distinct().OrderBy(x => x).ToList();
                onNote?.Invoke(episodes.Count + " 集超过校验上限 " + cap + "，抽样校验 " + verifyIdx.Count
                               + " 集（首尾各 12 + 中间均匀），其余按可播写入");
            }

            var live = new bool[episodes.Count];
            foreach (var i in verifyIdx) live[i] = true;   // 抽样模式下未抽到的默认可播

            var sem = new SemaphoreSlim(4);   // 2 核机：6 路并发会把线程池和带宽都打满
            var tasks = new List<Task>();
            int resolved = 0, attempted = 0;
            foreach (var i in verifyIdx)
            {
                int idx = i;
                tasks.Add(Task.Run(async () =>
                {
                    await sem.WaitAsync(ct).ConfigureAwait(false);
                    try
                    {
                        ThrowIfStopped(ct);
                        var url = episodes[idx]?.Url;
                        bool alreadyResolved = episodes[idx]?.Resolved == true;
                        if (!alreadyResolved && cfg.ResolveIndirectUrl && !PlayUrlResolver.LooksLikeDirectMedia(url))
                        {
                            Interlocked.Increment(ref attempted);
                            var real = await PlayUrlResolver.ResolveAsync(client.Http, url, site?.Referer, ct)
                                                            .ConfigureAwait(false);
                            if (!string.IsNullOrWhiteSpace(real))
                            {
                                episodes[idx].Url = real;   // 回写：strm 里写能播的那个
                                url = real;
                                Interlocked.Increment(ref resolved);
                            }
                        }
                        var (ok, why) = await client.VerifyPlayableAsync(url, site?.Referer, ct).ConfigureAwait(false);
                        live[idx] = ok;
                        if (!ok) onDead?.Invoke(why);
                    }
                    catch (OperationCanceledException)
                    {
                        if (ct.IsCancellationRequested || _stopRequested) throw;   // 真·用户取消
                        live[idx] = false;                                        // 只是这一集超时
                    }
                    catch { live[idx] = false; }
                    finally { sem.Release(); }
                }, ct));
            }
            try { await Task.WhenAll(tasks).ConfigureAwait(false); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            finally { sem.Dispose(); }

            if (attempted > 0)
                onNote?.Invoke("间接地址解析: 尝试 " + attempted + " 个，解出 " + resolved + " 个"
                               + (resolved == 0 ? "（解析接口/播放页不可达，或里面没有 m3u8）" : ""));

            for (int i = 0; i < episodes.Count; i++)
                if (live[i]) result.Add(episodes[i]);
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

            _stopRequested = false;                      // 新一轮开始，清掉上一轮的停止标志
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

                    _outputRoot = outputRoot;
                    var writer = new StrmWriter(outputRoot, m => _log("[strm] " + m));
                    writer.WriteNfo = cfg.WriteNfo;
                    writer.DownloadPoster = cfg.DownloadPoster;
                    writer.PosterMaxBytes = Math.Max(0, cfg.PosterMaxMb) * 1024 * 1024;
                    writer.MaxDeletePerRun = cfg.MaxDeletePerRun;
                    writer.NameTemplate = cfg.NameTemplate;
                    writer.RenameRules = ParseLineList(cfg.RenameRules);
                    writer.SkipKeywords = SplitCsv(cfg.SkipKeywords);
                    writer.DownloadSubtitle = cfg.DownloadSubtitle;
                    if (writer.SkipKeywords.Count > 0)
                        _log("跳过关键词: " + string.Join(" / ", writer.SkipKeywords));
                    if (!string.IsNullOrWhiteSpace(cfg.NameTemplate) && cfg.NameTemplate != "{name} ({year})")
                        _log("命名模板: " + cfg.NameTemplate);
                    AttachTmdb(writer, cfg);
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
                    // ── 播放链路初始化（直链缓存 + 签名）──
                    // 不要在这里 Clear：每轮同步都清空的话，上一轮预热好的直链全废了
                    // （实测第二轮同步 0 集需要校验 → 缓存空着，重播又得打源站）。
                    // 条目自带 TTL，过期自然失效；密钥轮换也不影响缓存里的直链。
                    Core.PlayLinkCache.TtlMinutes = Math.Max(0, cfg.PlayCacheMinutes);
                    var signSecret = (cfg.StreamSign ?? string.Empty).Trim();
                    if (signSecret.Length == 0)
                    {
                        signSecret = Guid.NewGuid().ToString("N");
                        cfg.StreamSign = signSecret;
                        try { saveConfig?.Invoke(); } catch { }
                        _log("已生成播放签名密钥（StreamSign），strm 里的地址将带签名");
                    }
                    TvBox.Plugin.Api.TvBoxApi.SetStreamSign(signSecret, cfg.StreamSignRequired);
                    if (string.IsNullOrWhiteSpace(cfg.WebhookToken))
                    {
                        cfg.WebhookToken = Guid.NewGuid().ToString("N");
                        try { saveConfig?.Invoke(); } catch { }
                        _log("已生成 Webhook 令牌（外部触发 / Emby 删除同步用）");
                    }
                    if (cfg.PlayCacheMinutes > 0)
                        _log("播放直链缓存: " + cfg.PlayCacheMinutes + " 分钟");
                    if (cfg.StreamSignRequired)
                        _log("播放签名: 强制校验（无签名请求将被拒绝）");

                    // 站点级 Referer：每个站点单独取，写进代理地址里，播放时才会带对 Referer
                    string siteReferer = null;
                    SetupPlayMode(cfg, embyBaseUrlForProxy);
                    if (!ProxyStyle(_playMode))
                    {
                        // 直链：源站地址原样写进 strm，播放器直连源站，外网也能播
                        writer.UrlRewriter = u => u;
                    }
                    else if (cfg.UseProxy && !string.IsNullOrWhiteSpace(_embyBase))
                    {
                        var baseUrl = _embyBase;
                        if (IsLoopbackBase(baseUrl))
                        {
                            summary.Detail = "输出地址解析成了本机回环 " + baseUrl
                                + "：客户端（电视/手机）拿到 127.0.0.1 会去连自己，写出来的 strm 全部播不了。"
                                + "请到插件设置里把「Emby 访问地址」填成局域网地址（如 http://192.168.1.10:8096）。";
                            _log("[abort] " + summary.Detail);
                            Update(p => p.LastError = summary.Detail);
                            Finish(summary, cfg, saveConfig);
                            return false;
                        }
                        writer.UrlRewriter = u =>
                        {
                            // /TvBox/Play 自己就是代理（内部向 hub 解析后再转发 m3u8），
                            // 再包一层 /TvBox/Stream 会变成 Stream→Play 双跳，
                            // 而 Stream 的 SSRF 防护会把内网地址判成 blocked host。
                            if (u != null && u.Contains("/TvBox/Play?", StringComparison.Ordinal)) return u;
                            return baseUrl + TvBox.Plugin.Api.TvBoxApi.BuildProxyPath(
                                u, string.IsNullOrWhiteSpace(cfg.Referer) ? siteReferer : cfg.Referer);
                        };
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
                        ThrowIfStopped(token);
                        ThrowIfStopped(ct);
                        siteIdx++;
                        Update(p => { p.SiteIndex = siteIdx; p.CurrentSite = site.DisplayName; });
                        siteReferer = site.Referer;
                        _log($"── [{siteIdx}/{sites.Count}] {site.DisplayName} ({site.Api})");

                        try
                        {
                            // 用 token（linked，含 _cts）而不是 ct：否则点「停止」只影响
                            // 站点级检查点，正在跑的逐集校验还要等满 TimeoutSeconds 才退出。
                            var n = await SyncOneSiteAsync(site, cfg, writer, keep, token, summary).ConfigureAwait(false);
                            _log($"   {site.DisplayName}: {n} 个条目");
                            // 成功一轮把失败计数清零
                            try { cfg.ResetFailStreak(site.Key); } catch { }
                            if (n > 0) await ReportSiteAsync(cfg, site.Key, true, ct).ConfigureAwait(false);
                        }
                        catch (OperationCanceledException) when (ct.IsCancellationRequested || token.IsCancellationRequested || _stopRequested) { throw; }
                        catch (Exception ex) when (_stopRequested)
                        {
                            // 用户主动停止时这里的异常不是「站点坏了」：既不计失败，
                            // 也不上报 /api/sites/fail（否则 hub 会把健康站点移出同步名单）。
                            throw new OperationCanceledException("用户请求停止同步", ex);
                        }
                        catch (Exception ex)
                        {
                            summary.SitesFailed++;
                            summary.Errors++;
                            _log($"   站点失败: {ex.Message}");
                            // ── 站点熔断：连续失败到阈值就自动停用，别再每轮都白等超时 ──
                            if (cfg.SiteFailThreshold > 0)
                            {
                                try
                                {
                                    var streak = cfg.BumpFailStreak(site.Key);
                                    _log($"   连续失败 {streak}/{cfg.SiteFailThreshold} 次");
                                    if (streak >= cfg.SiteFailThreshold)
                                    {
                                        if (cfg.DeadSites == null) cfg.DeadSites = new List<string>();
                                        if (!cfg.DeadSites.Contains(site.Key, StringComparer.OrdinalIgnoreCase))
                                            cfg.DeadSites.Add(site.Key);
                                        _log($"⛔ 站点「{site.DisplayName}」连续失败 {streak} 次，已自动移出同步（配置页可一键恢复）");
                                        try { saveConfig?.Invoke(); } catch { }
                                    }
                                }
                                catch { }
                            }
                            await ReportSiteAsync(cfg, site.Key, false, ct).ConfigureAwait(false);
                            Update(p => { p.Errors++; p.LastError = site.DisplayName + ": " + ex.Message; });
                        }
                    }

                    // ── 4. 清理孤儿 ──
                    // 只有「本轮跑完整了」才允许清理：被 MaxItemsPerRun 截断、或者有站点失败时，
                    // keep 里缺的是「没来得及处理」而不是「已失效」，照删就是成片误删用户的 strm。
                    var truncated = summary.Truncated;
                    if (cfg.CleanupOrphans && summary.SitesFailed == 0 && !truncated)
                    {
                        Update(p => p.Stage = "清理失效条目");
                        var removed = writer.CleanupOrphans(keep, out var removedDirs);
                        summary.Removed = removed;
                        _log($"清理了 {removed} 个失效 strm、{removedDirs} 个空目录");
                        if (writer.DeletesSkipped > 0)
                            _log("⚠️ " + writer.LastError);
                    }
                    else if (cfg.CleanupOrphans)
                    {
                        _log(truncated
                            ? "本轮被 MaxItemsPerRun 截断，跳过孤儿清理（避免误删没处理到的条目）"
                            : "本轮有站点失败（" + summary.SitesFailed + "），跳过孤儿清理");
                    }
                    // 清单每轮都写：否则关掉清理期间新写的文件永远不进清单，之后也清不掉。
                    try { writer.SaveManifest(keep); } catch (Exception ex) { _log("写清单失败: " + ex.Message); }
                }

                try { state.Save(force: true); } catch { }
                if (state.SkippedHits > 0)
                    _log("增量同步命中: " + state.SkippedHits + " 个条目直接跳过详情请求");

                var written = Math.Max(0, summary.Items - summary.Skipped);
                summary.Detail = $"站点 {summary.Sites}（失败 {summary.SitesFailed}），条目 {summary.Items}"
                               + $"（写入 {written}，跳过 {summary.Skipped}），分集 {summary.Episodes}，"
                               + (summary.Unchanged > 0 ? $"（其中 {summary.Unchanged} 个内容未变）" : "")
                               + $"清理 {summary.Removed}，错误 {summary.Errors}";
                // 区分两种情况：真的写不进磁盘，还是所有条目本来就没法播。
                // 之前一律提示"检查输出目录"，实测有源 37 个条目全是解析接口失效，
                // 用户照着提示去查目录权限，方向完全错了。
                if (summary.Items > 0 && written == 0 && summary.Unchanged == 0)
                {
                    if (!string.IsNullOrEmpty(_lastWriteError))
                        summary.Detail += "  ⚠️ 写入失败：" + _lastWriteError;
                    else if (summary.Skipped > 0)
                        summary.Detail += "  ⚠️ 全部 " + summary.Skipped
                                        + " 个条目都因播放地址不可用被跳过（原因见日志，换几个源试试）";
                    else
                        summary.Detail += "  ⚠️ 全部条目都没写进磁盘（检查输出目录是否存在且 Emby 用户可写）";
                }
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
                ThrowIfStopped(ct);
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
                    ThrowIfStopped(ct);
                    if (cfg.MaxItemsPerRun > 0 && summary.Items >= cfg.MaxItemsPerRun) { summary.Truncated = true; break; }
                    if (string.IsNullOrWhiteSpace(item.Id)) continue;
                    seen++;
                    // 增量：列表侧指纹没变且还在有效期内 → 详情都不拉
                    if (TrySkipByIncremental(cfg, site, item, keep, summary)) continue;
                    try
                    {
                        var details = await client.GetDetailAsync(site, new[] { item.Id }, ct).ConfigureAwait(false);
                        var detail = details != null && details.Count > 0 ? details[0] : null;
                        if (detail == null) { summary.Skipped++; Update(p => p.ItemsSkipped++); Record("skipped", name, item.Name, "拉不到详情"); continue; }

                        var line = detail.Lines != null && detail.Lines.Count > 0 ? detail.Lines[0] : null;
                        if (line?.Episodes == null || line.Episodes.Count == 0)
                        {
                            summary.Skipped++; Update(p => p.ItemsSkipped++);
                            continue;
                        }

                        // 大量 spider 的 vod_play_url 不是 URL，而是它自己的分集 id
                        // （金牌影视 146660@1314815 / 半日99 CO4K_… / Sao火 /play/x.html）。
                        // TVBox 客户端播之前都会调 playerContent 换真地址，
                        // 这里同样交给 hub 的 /play/ 端点，校验和写 strm 都用换出来的地址。
                        line = ResolvePrivateIds(line, site, cfg, item?.Id ?? detail?.Id);

                        if (line.Episodes.Count > 1)
                        {
                            if (cfg.VerifyPlayUrl)
                            {
                                // 逐集校验：一个源里经常是"前几集能播、后面几集全是图片广告"，
                                // 只验首集会放过大量死地址（实测 40 个样本里就有 1 个是 17 集坏了）。
                                int dead = 0;
                                var reasons = new System.Collections.Concurrent.ConcurrentDictionary<string, int>();
                                var live = await FilterPlayableEpisodesAsync(
                                    client, line.Episodes, site, cfg, ct,
                                    why => { dead++; reasons.AddOrUpdate(ShortReason(why), 1, (_, v) => v + 1); },
                                    note => _log("     [" + (item.Name ?? item.Id) + "] " + note)).ConfigureAwait(false);
                                // 线路兜底：TVBox 客户端是逐条线路试的，我们只挑「最佳线路」，
                                // 这条线挂了就整条跳过 —— 实测有源第 2、3 条线路是好的。
                                if (live.Count == 0 && detail.Lines != null && detail.Lines.Count > 1)
                                {
                                    foreach (var cand in detail.Lines.Skip(1).Take(3))
                                    {
                                        ThrowIfStopped(ct);
                                        if (cand?.Episodes == null || cand.Episodes.Count == 0) continue;
                                        var candLine = ResolvePrivateIds(new VodLine { Name = cand.Name, Episodes = cand.Episodes }, site, cfg, item?.Id ?? detail?.Id);
                                        int dead2 = 0;
                                        var live2 = await FilterPlayableEpisodesAsync(client, candLine.Episodes, site, cfg, ct,
                                            _ => dead2++, _ => { }).ConfigureAwait(false);
                                        if (live2.Count > 0)
                                        {
                                            _log("     [" + (item.Name ?? item.Id) + "] 线路兜底：换到「" + cand.Name + "」(" + live2.Count + " 集)");
                                            line = new VodLine { Name = candLine.Name, Episodes = live2 };
                                            live = live2;
                                            break;
                                        }
                                    }
                                }
                                if (live.Count == 0)
                                {
                                    // 整条都解不出：先试换源（同片在别的站可能好好的）
                                    int altN = await TryCrossSiteWriteAsync(cfg, item.Name ?? detail.Name,
                                        string.IsNullOrWhiteSpace(detail.Year) ? item.Year : detail.Year,
                                        site.Key, ct).ConfigureAwait(false);
                                    if (altN > 0)
                                    {
                                        summary.Items++;
                                        summary.Episodes += altN;
                                        written++;
                                        Update(p => { p.ItemsWritten++; p.EpisodesWritten += altN; });
                                        continue;
                                    }
                                    summary.Skipped++; Update(p => p.ItemsSkipped++);
                                    Record("skipped", name, item.Name, line.Episodes.Count + " 集全部不可用" + FormatReasons(reasons));
                                    _log("     [" + (item.Name ?? item.Id) + "] 跳过：" + line.Episodes.Count + " 集全部不可用" + FormatReasons(reasons));
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
                            Record("written", name, item.Name, added + " 集");
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
                                var (pok, why) = await client.VerifyPlayableAsync(epUrl, site?.Referer, ct).ConfigureAwait(false);
                                if (!pok)
                                {
                                    int altM = await TryCrossSiteWriteAsync(cfg, item.Name ?? detail.Name,
                                        string.IsNullOrWhiteSpace(detail.Year) ? item.Year : detail.Year,
                                        site.Key, ct).ConfigureAwait(false);
                                    if (altM > 0)
                                    {
                                        summary.Items++;
                                        summary.Episodes += altM;
                                        written++;
                                        Update(p => { p.ItemsWritten++; p.EpisodesWritten += altM; });
                                        continue;
                                    }
                                    summary.Skipped++; Update(p => p.ItemsSkipped++);
                                    Record("skipped", name, item.Name, "播放地址不可用（" + why + "）");
                                    _log("     [" + (item.Name ?? item.Id) + "] 跳过：播放地址不可用（" + why + "）");
                                    if (cfg.RequestDelayMs > 0) await Task.Delay(cfg.RequestDelayMs, ct).ConfigureAwait(false);
                                    continue;
                                }
                            }
                            var o = writer.WriteMovie(name, detail, line);
                            if (!string.IsNullOrEmpty(o.Path)) keep.Add(o.Path);
                            RecordIncremental(site, item, detail, new[] { o });
                            Record("written", name, item.Name, "电影");
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
                        Record("error", name, item.Name, ex.Message);
                        _log("     [" + (item.Name ?? item.Id) + "] 失败: " + ex.Message);
                    }
                    if (cfg.RequestDelayMs > 0) await Task.Delay(cfg.RequestDelayMs, ct).ConfigureAwait(false);
                }
                if (cfg.MaxItemsPerRun > 0 && summary.Items >= cfg.MaxItemsPerRun) { summary.Truncated = true; break; }
                if (res.Items.Count < 20) break;
            }
            _log("     没分类但直接拉到 " + seen + " 条，写入 " + written + " 条");
            return written;
        }

        // ── 同步记录（界面「同步记录」页的数据源）────────────────────
        // 只记最近 400 条（环形队列），进程内存态：重启清零，但不影响同步本身
        public sealed class SyncRec
        {
            public long Ts;          // epoch ms
            public string Site;     // 站点显示名
            public string Name;     // 条目名
            public string Status;   // written / skipped / error / 换源
            public string Info;     // 一句话详情
        }

        static readonly System.Collections.Concurrent.ConcurrentQueue<SyncRec> _records =
            new System.Collections.Concurrent.ConcurrentQueue<SyncRec>();
        public static System.Collections.Concurrent.ConcurrentQueue<SyncRec> Records => _records;

        static void Record(string status, string site, string name, string info)
        {
            _records.Enqueue(new SyncRec
            {
                Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                Site = site ?? "", Name = name ?? "", Status = status ?? "", Info = info ?? "",
            });
            while (_records.Count > 400) _records.TryDequeue(out _);
        }

        /// <summary>片名归一化：去括号里的内容、去年份、只留字母数字（中文按字母算），统一小写。</summary>
        private static string NormalizeTitle(string s)
        {
            if (string.IsNullOrWhiteSpace(s)) return string.Empty;
            var t = System.Text.RegularExpressions.Regex.Replace(s, @"[\(（\[【].*?[\)）\]】]", " ");
            t = System.Text.RegularExpressions.Regex.Replace(t, @"(19|20)\d{2}", " ");
            var sb = new System.Text.StringBuilder(t.Length);
            foreach (var ch in t)
                if (char.IsLetterOrDigit(ch)) sb.Append(char.ToLowerInvariant(ch));
            return sb.ToString();
        }

        /// <summary>
        /// 跨站换源：这个条目在自己站点里整条解不出（或全部分集不可播）时，
        /// 去其它站点搜同名影片，用能播的那个站写一份 —— 就是 TVBox 里「换源」那件事。
        ///
        /// 为什么要它：单个站点失效（spider 改版、解析 key 过期、整站换域名）是常态，
        /// 而同一部片子在 152 个站里的重合度极高，换一个站九成还能看。
        /// 返回写入的分集数（电影成功写 1）；没换成返回 0。
        /// </summary>
        private async Task<int> TryCrossSiteWriteAsync(PluginConfiguration cfg, string name, string year,
            string excludeKey, CancellationToken ct)
        {
            if (!cfg.CrossSiteFallback || string.IsNullOrWhiteSpace(name)) return 0;
            var hub = (cfg.SpiderHubUrl ?? string.Empty).Trim().TrimEnd('/');
            if (hub.Length == 0) return 0;
            var kw = NormalizeTitle(name);
            if (kw.Length < 2) return 0;

            string body;
            try
            {
                using var searchHttp = new HttpClient { Timeout = TimeSpan.FromSeconds(60) };
                body = await searchHttp.GetStringAsync(hub + "/api/search-all?wd=" + Uri.EscapeDataString(kw), ct)
                                        .ConfigureAwait(false);
            }
            catch (Exception ex) { _log("     [换源] 搜索失败: " + ShortErr(ex)); return 0; }

            // 选候选：片名归一化后一致优先，其次包含；年份能对上再加分
            var cands = new List<(string key, string siteName, string id, string name, int score)>();
            try
            {
                using var doc = System.Text.Json.JsonDocument.Parse(body);
                if (!doc.RootElement.TryGetProperty("items", out var items)
                    || items.ValueKind != System.Text.Json.JsonValueKind.Array) return 0;
                var wantYear = (year ?? string.Empty).Trim();
                foreach (var it in items.EnumerateArray())
                {
                    var key = it.TryGetProperty("site", out var sEl) ? (sEl.GetString() ?? "") : "";
                    var id = it.TryGetProperty("id", out var iEl) ? (iEl.GetString() ?? "") : "";
                    var nm = it.TryGetProperty("name", out var nEl) ? (nEl.GetString() ?? "") : "";
                    var yr = it.TryGetProperty("year", out var yEl) ? (yEl.GetString() ?? "") : "";
                    var siteName = it.TryGetProperty("siteName", out var snEl) ? (snEl.GetString() ?? "") : "";
                    if (key.Length == 0 || id.Length == 0) continue;
                    if (string.Equals(key, excludeKey, StringComparison.OrdinalIgnoreCase)) continue;
                    var nn = NormalizeTitle(nm);
                    if (nn.Length == 0) continue;
                    int score = nn == kw ? 3 : (nn.Contains(kw) || kw.Contains(nn) ? 2 : 0);
                    if (score == 0) continue;
                    if (wantYear.Length > 0 && yr.Length > 0) score += (wantYear == yr) ? 1 : -1;
                    cands.Add((key, string.IsNullOrWhiteSpace(siteName) ? key : siteName, id, nm, score));
                }
            }
            catch (Exception ex) { _log("     [换源] 解析搜索结果失败: " + ShortErr(ex)); return 0; }

            if (cands.Count == 0) return 0;
            var limit = Math.Max(1, cfg.CrossSiteMaxCandidates);
            foreach (var cand in cands.OrderByDescending(c => c.score).ThenBy(c => c.key == excludeKey).Take(limit))
            {
                try
                {
                    ThrowIfStopped(ct);
                    using var altHttp = CreateClient(cfg);
                    var altClient = new CmsClient(altHttp, m => _log("       [换源] " + m), cfg.Referer);
                    var altSite = new TvBoxSite
                    {
                        Key = cand.key,
                        Name = cand.siteName,
                        Api = hub + "/v/" + Uri.EscapeDataString(cand.key) + "/api.php/provide/vod/",
                    };
                    var dets = await altClient.GetDetailAsync(altSite, new[] { cand.id }, ct).ConfigureAwait(false);
                    var det = dets != null && dets.Count > 0 ? dets[0] : null;
                    if (det == null) continue;
                    var altLine = PlayUrlParser.PickBestLine(det);
                    if (altLine?.Episodes == null || altLine.Episodes.Count == 0) continue;
                    altLine = ResolvePrivateIds(altLine, altSite, cfg, det.Id);
                    var liveAlt = cfg.VerifyPlayUrl
                        ? await FilterPlayableEpisodesAsync(altClient, altLine.Episodes, altSite, cfg, ct,
                              _ => { }, _ => { }).ConfigureAwait(false)
                        : altLine.Episodes;
                    if (liveAlt.Count == 0) continue;
                    altLine = new VodLine { Name = altLine.Name, Episodes = liveAlt };

                    var writer2 = new StrmWriter(_outputRoot, m => _log("       [换源] " + m));
                    writer2.WriteNfo = cfg.WriteNfo;
                    writer2.DownloadPoster = cfg.DownloadPoster;
                    writer2.PosterMaxBytes = Math.Max(0, cfg.PosterMaxMb) * 1024 * 1024;
                    writer2.MaxDeletePerRun = 0;                     // 换源写文件绝不做清理
                    writer2.NameTemplate = cfg.NameTemplate;
                    writer2.RenameRules = ParseLineList(cfg.RenameRules);
                    writer2.SkipKeywords = SplitCsv(cfg.SkipKeywords);
                    writer2.DownloadSubtitle = cfg.DownloadSubtitle;
                    AttachTmdb(writer2, cfg);
                    if (!ProxyStyle(_playMode)) writer2.UrlRewriter = u => u;
                    else if (cfg.UseProxy && !string.IsNullOrWhiteSpace(_embyBase))
                    {
                        var bu = _embyBase;
                        writer2.UrlRewriter = u => u != null && u.Contains("/TvBox/Play?", StringComparison.Ordinal)
                            ? u : bu + TvBox.Plugin.Api.TvBoxApi.BuildProxyPath(u, cfg.Referer);
                    }

                    int added = 0;
                    if (liveAlt.Count > 1)
                    {
                        var outs = writer2.WriteSeries(cand.siteName, det, altLine);
                        added = outs.Count(o => o.Created || o.Updated);
                    }
                    else
                    {
                        var o = writer2.WriteMovie(cand.siteName, det, altLine);
                        if (o.Created || o.Updated) added = 1;
                    }
                    if (added > 0)
                    {
                        _log("     [换源] " + (name ?? det.Name) + " → 换到「" + cand.siteName + "」写入 " + added + " 集");
                        Record("换源", cand.siteName, name ?? det.Name, "原站解不出，换源写入 " + added + " 集");
                        return added;
                    }
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                catch (Exception ex) { _log("     [换源] " + cand.siteName + " 失败: " + ShortErr(ex)); }
            }
            return 0;
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
                ThrowIfStopped(ct);
                all.AddRange(await GetDetailBatchAsync(client, site, batch.ToList(), cfg, ct, 0).ConfigureAwait(false));
            }
            return all;
        }

        private async Task<List<VodItem>> GetDetailBatchAsync(
            CmsClient client, TvBoxSite site, List<string> batch, PluginConfiguration cfg,
            CancellationToken ct, int depth)
        {
            ThrowIfStopped(ct);      // 拆批重试会递归很多层，每层都要能立刻退出
            try
            {
                var r = await client.GetDetailAsync(site, batch, ct).ConfigureAwait(false);
                return r ?? new List<VodItem>();
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested || _stopRequested) { throw; }
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

        /// <summary>
        /// 输出地址是不是本机回环。填成回环时写出的 strm 客户端一律播不了，
        /// 与其静默生成一个死库，不如直接拒绝并让用户去改配置。
        /// </summary>
        private static bool IsLoopbackBase(string baseUrl)
        {
            if (string.IsNullOrWhiteSpace(baseUrl)) return true;
            try
            {
                var host = new Uri(baseUrl).Host;
                if (host.Equals("localhost", StringComparison.OrdinalIgnoreCase)) return true;
                if (host == "::1" || host == "[::1]") return true;
                if (System.Net.IPAddress.TryParse(host, out var ip))
                    return System.Net.IPAddress.IsLoopback(ip);
                return false;
            }
            catch { return false; }
        }

        /// <summary>把校验失败原因归一成短标签，便于在日志里做分布统计。</summary>
        private static string ShortReason(string why)
        {
            if (string.IsNullOrWhiteSpace(why)) return "未知";
            var w = why.Trim();
            if (w.StartsWith("源返回 ")) return w.Substring(4).Trim();
            if (w.StartsWith("content-type=")) return w.Substring(13).Trim();
            if (w.Contains("Timeout", StringComparison.OrdinalIgnoreCase)) return "超时";
            return w.Length > 24 ? w.Substring(0, 24) : w;
        }

        /// <summary>「（原因：403×12、text/html×6）」——只报"全部不可用"看不出是防盗链还是源死了。</summary>
        private static string FormatReasons(System.Collections.Concurrent.ConcurrentDictionary<string, int> reasons)
        {
            if (reasons == null || reasons.Count == 0) return "";
            var top = reasons.OrderByDescending(kv => kv.Value).Take(3)
                             .Select(kv => kv.Key + "×" + kv.Value);
            return "（原因：" + string.Join("、", top) + "）";
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
            // 直链会过期（源站 auth_key 一般 1~3 天）：把跳过窗口压到 DirectRefreshHours，
            // 到点就重新拉详情 + 重解析，strm 里的地址跟着刷新
            var skipHours = cfg.IncrementalHours;
            if (_playMode != "proxy") skipHours = Math.Min(skipHours <= 0 ? 24 : skipHours, Math.Max(1, cfg.DirectRefreshHours));
            if (!_state.TrySkip(site.Key, listed.Id, SyncState.Signature(listed), skipHours, out var cached,
                                listed.Time, cfg.UseItemTimeCursor))
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
            _state.Record(site.Key, baseItem.Id, SyncState.Signature(baseItem), paths, baseItem.Time);
        }

        /// <summary>
        /// 海报下载（同步调用，带大小上限，失败返回 null）。
        ///
        /// 这里必须用 HttpClient 的**同步** Send/ReadAsStream，不能用
        /// GetAsync(...).GetAwaiter().GetResult()：那是"同步等异步"，
        /// 每张海报都要占住一个线程去等续体，而这个回调是在写文件的同步流程里被调用的。
        /// 一台 2 核机器同步几百个条目时，线程池被抽干 → Emby 整个 Web 服务失去响应，
        /// 表现就是「点了停止没反应」（连 /System/Info/Public 都超时）。
        /// 同步 API 走的是纯阻塞 IO，不占用线程池续体，不会再拖死服务。
        /// </summary>
        private static byte[] FetchImageBytes(HttpClient http, string url, int maxBytes)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(url)) return null;
                using (var req = new HttpRequestMessage(HttpMethod.Get, url))
                using (var resp = http.Send(req, HttpCompletionOption.ResponseHeadersRead))
                {
                    if (!resp.IsSuccessStatusCode) return null;
                    var len = resp.Content.Headers.ContentLength;
                    if (maxBytes > 0 && len.HasValue && len.Value > maxBytes) return null;
                    using (var st = resp.Content.ReadAsStream())
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
                    ThrowIfStopped(ct);

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
                        ThrowIfStopped(ct);
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
                            // 有分类的主路径之前漏了这一步：私有分集 id（146660@1314815 这类）
                            // 在这里没换成 hub /play/ 地址，就被当成死链 404 全跳过。
                            line = ResolvePrivateIds(line, site, cfg, item?.Id);

                            if (item.IsSeries)
                            {
                                // 逐集校验（这条才是"有分类"的主路径，之前只校验了无分类降级路径）
                                if (cfg.VerifyPlayUrl)
                                {
                                    int dead = 0;
                                    var reasons = new System.Collections.Concurrent.ConcurrentDictionary<string, int>();
                                    var live = await FilterPlayableEpisodesAsync(
                                        client, line.Episodes, site, cfg, ct,
                                        why => { dead++; reasons.AddOrUpdate(ShortReason(why), 1, (_, v) => v + 1); },
                                        note => _log("     [" + (item.Name ?? item.Id) + "] " + note)).ConfigureAwait(false);
                                    // 线路兜底：TVBox 客户端是逐条线路试的，我们只挑「最佳线路」，
                                    // 这条线挂了就整条跳过 —— 实测有源第 2、3 条线路是好的。
                                    if (live.Count == 0 && item.Lines != null && item.Lines.Count > 1)
                                    {
                                        foreach (var cand in item.Lines.Skip(1).Take(3))
                                        {
                                            ThrowIfStopped(ct);
                                            if (cand?.Episodes == null || cand.Episodes.Count == 0) continue;
                                            var candLine = ResolvePrivateIds(new VodLine { Name = cand.Name, Episodes = cand.Episodes }, site, cfg, item?.Id);
                                            int dead2 = 0;
                                            var live2 = await FilterPlayableEpisodesAsync(client, candLine.Episodes, site, cfg, ct,
                                                _ => dead2++, _ => { }).ConfigureAwait(false);
                                            if (live2.Count > 0)
                                            {
                                                _log("     [" + (item.Name ?? item.Id) + "] 线路兜底：换到「" + cand.Name + "」(" + live2.Count + " 集)");
                                                line = new VodLine { Name = candLine.Name, Episodes = live2 };
                                                live = live2;
                                                break;
                                            }
                                        }
                                    }
                                    if (live.Count == 0)
                                    {
                                        summary.Items++;
                                        summary.Skipped++;
                                        Update(p => p.ItemsSkipped++);
                                        Record("skipped", site.DisplayName, item.Name, line.Episodes.Count + " 集全部不可用" + FormatReasons(reasons));
                                        _log("     [" + (item.Name ?? item.Id) + "] 跳过：" + line.Episodes.Count + " 集全部不可用" + FormatReasons(reasons));
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
                                    Record("written", site.DisplayName, item.Name, added + " 集" + (same > 0 ? "（" + same + " 未变）" : ""));
                                    summary.Episodes += added;
                                    summary.Unchanged += same;
                                    Update(p => { p.ItemsWritten++; p.EpisodesWritten += added; });
                                }
                                else
                                {
                                    summary.Items++;
                                    { summary.Skipped++; Update(p => p.ItemsSkipped++); if (!string.IsNullOrEmpty(writer.LastError)) _lastWriteError = writer.LastError; }
                                    Record("skipped", site.DisplayName, item.Name, "写入 0 文件" + (string.IsNullOrEmpty(writer.LastError) ? "" : "：" + writer.LastError));
                                }
                            }
                            else
                            {
                                // 电影分支也要校验（之前只有剧集分支校验，死链电影照样写出来）
                                if (cfg.VerifyPlayUrl)
                                {
                                    var mvUrl = line.Episodes[0]?.Url;
                                    var (mok, mwhy) = await client.VerifyPlayableAsync(mvUrl, site?.Referer, ct).ConfigureAwait(false);
                                    if (!mok)
                                    {
                                        summary.Items++;
                                        summary.Skipped++;
                                        Update(p => p.ItemsSkipped++);
                                        Record("skipped", site.DisplayName, item.Name, "播放地址不可用（" + mwhy + "）");
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
                                    Record("written", site.DisplayName, item.Name, "电影");
                                    Update(p => { p.ItemsWritten++; p.EpisodesWritten++; });
                                }
                                else
                                {
                                    summary.Items++;
                                    { summary.Skipped++; Update(p => p.ItemsSkipped++); if (!string.IsNullOrEmpty(writer.LastError)) _lastWriteError = writer.LastError; }
                                    Record("skipped", site.DisplayName, item.Name, "写入 0 文件" + (string.IsNullOrEmpty(writer.LastError) ? "" : "：" + writer.LastError));
                                }
                            }

                            if (cfg.MaxItemsPerRun > 0 && summary.Items >= cfg.MaxItemsPerRun)
                            {
                                summary.Truncated = true;
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
