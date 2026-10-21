using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Controller.Net;
using MediaBrowser.Model.Services;
using TvBox.Plugin.Core;

namespace TvBox.Plugin.Api
{
    // ───────────────────── 请求 DTO ─────────────────────

    [Unauthenticated]   // 供 spider-hub 调用，靠 ?token= 校验（见 TvBoxApi.HubOk）
    [Route("/TvBox/Status", "GET", Summary = "获取 TVBox 同步状态")]
    public class GetStatus : IReturn<StatusResult>
    {
        /// <summary>spider-hub 调用时要带；Emby 配置页调用时留空则按已登录处理。</summary>
        public string Token { get; set; }
    }

    [Unauthenticated]
    [Route("/TvBox/Sources", "GET", Summary = "解析配置并列出所有站点")]
    public class GetSources : IReturn<SourcesResult>
    {
        public string Token { get; set; }
    }

    [Unauthenticated]
    [Route("/TvBox/Sync", "POST", Summary = "立即开始同步")]
    public class StartSync : IReturn<SimpleResult>
    {
        public string Token { get; set; }
    }

    [Unauthenticated]
    [Route("/TvBox/Stop", "POST", Summary = "停止正在进行的同步")]
    public class StopSync : IReturn<SimpleResult>
    {
        public string Token { get; set; }
    }

    /// <summary>以 Emby 进程身份测试目录是否可写（只有真跑一下才知道，看权限位不可靠）。</summary>
    [Unauthenticated]
    [Route("/TvBox/ProbeWrite", "GET", Summary = "测试目录对 Emby 是否可写")]
    public class ProbeWrite : IReturn<WriteProbeResult>
    {
        public string Token { get; set; }
        public string Path { get; set; }
    }

    public class WriteProbeItem
    {
        public string Path { get; set; }
        public bool Exists { get; set; }
        public bool Writable { get; set; }
        public string Error { get; set; }
    }

    public class WriteProbeResult
    {
        public bool Ok { get; set; }
        public string Message { get; set; }
        public string User { get; set; }
        public string Error { get; set; }
        public System.Collections.Generic.List<WriteProbeItem> Items { get; set; }
            = new System.Collections.Generic.List<WriteProbeItem>();
    }

    /// <summary>同步范围配置：hub 的 Web 界面直接读写，不用进 Emby 后台。</summary>
    [Unauthenticated]
    [Route("/TvBox/Config", "POST", Summary = "更新同步范围（白名单/页数/并发等）")]
    public class UpdateConfig : IReturn<SimpleResult>
    {
        public string Token { get; set; }
        // ── 采集源 ──
        public string ConfigUrl { get; set; }
        public string SiteNameFilter { get; set; }
        /// <summary>spider 中转地址，由 spider-hub 自动填写（它知道自己的端口）。</summary>
        public string SpiderHubUrl { get; set; }
        // ── 同步范围 ──
        public string SiteWhitelist { get; set; }
        public string SiteBlacklist { get; set; }
        public int? MaxPagesPerCategory { get; set; }
        public int? MaxItemsPerRun { get; set; }
        public int? Concurrency { get; set; }
        public int? TimeoutSeconds { get; set; }
        public int? RequestDelayMs { get; set; }
        public bool? ExcludeCloudDrive { get; set; }
        // ── 输出与播放 ──
        public string OutputRoot { get; set; }
        public string EmbyBaseUrl { get; set; }
        public string Referer { get; set; }
        public string UserAgent { get; set; }
        public bool? UseProxy { get; set; }
        public bool? AllowPrivateHosts { get; set; }
        public bool? CleanupOrphans { get; set; }
        // ── 定时 ──
        public bool? EnableScheduledSync { get; set; }
        public int? SyncIntervalHours { get; set; }
    }

    /// <summary>
    /// 读取插件完整配置。spider-hub 的 Web 界面用它把「配置」页渲染成和插件配置页
    /// 完全相同的字段与取值 —— 两边共用一个真相源（plugins/configurations/TvBox.xml）。
    /// </summary>
    [Unauthenticated]
    [Route("/TvBox/Config", "GET", Summary = "读取插件完整配置")]
    public class GetConfig : IReturn<ConfigResult>
    {
        public string Token { get; set; }
    }

    public class ConfigResult : SimpleResult
    {
        public string ConfigUrl { get; set; }
        public string SpiderHubUrl { get; set; }
        public string OutputRoot { get; set; }
        public string EmbyBaseUrl { get; set; }
        public string SiteWhitelist { get; set; }
        public string SiteBlacklist { get; set; }
        public string SiteNameFilter { get; set; }
        public int MaxPagesPerCategory { get; set; }
        public int MaxItemsPerRun { get; set; }
        public int Concurrency { get; set; }
        public int RequestDelayMs { get; set; }
        public int TimeoutSeconds { get; set; }
        public string UserAgent { get; set; }
        public string Referer { get; set; }
        public bool UseProxy { get; set; }
        public bool AllowPrivateHosts { get; set; }
        public bool CleanupOrphans { get; set; }
        public bool ExcludeCloudDrive { get; set; }
        public bool EnableScheduledSync { get; set; }
        public int SyncIntervalHours { get; set; }
        /// <summary>配置文件绝对路径，界面上显示便于排查。</summary>
        public string ConfigFilePath { get; set; }
        /// <summary>只读字段（由安装器/运行环境决定）。</summary>
        public string[] ReadOnly { get; set; }
    }

    [Unauthenticated]
    [Route("/TvBox/Probe", "GET", Summary = "探测单个站点的分类和条目数")]
    public class ProbeSite : IReturn<ProbeResult>
    {
        public string Token { get; set; }
        public string SiteKey { get; set; }
        public int Page { get; set; }
    }

    [Unauthenticated]
    [Route("/TvBox/Stream/{Name}", "GET", Summary = "播放地址代理（补 Referer / UA，并重写 HLS 分片地址）")]
    [Route("/TvBox/Stream", "GET", Summary = "播放地址代理（兼容无扩展名写法）")]
    public class StreamProxy : IReturnVoid
    {
        /// <summary>
        /// 仅用于让 URL 带上原始扩展名（.m3u8 / .ts / .m4s ...）。
        /// ffmpeg 的 HLS 解复用器会校验分片扩展名，没有就直接报
        /// "not in allowed_segment_extensions" 而拒绝播放。
        /// </summary>
        public string Name { get; set; }

        public string U { get; set; }
        public string R { get; set; }
    }

    // ───────────────────── 返回模型 ─────────────────────

    public class SimpleResult
    {
        public bool Success { get; set; }
        public string Message { get; set; }
    }

    public class StatusResult
    {
        public bool Running { get; set; }
        public string Stage { get; set; }
        public string CurrentSite { get; set; }
        public int SiteIndex { get; set; }
        public int SiteTotal { get; set; }
        public int ItemsWritten { get; set; }
        public int EpisodesWritten { get; set; }
        public int Errors { get; set; }
        public string LastError { get; set; }
        public string OutputRoot { get; set; }
        public string LastSyncUtc { get; set; }
        public string LastSyncSummary { get; set; }

        // 同步范围（hub 界面显示 + 编辑）
        public string ConfigUrl { get; set; }
        public string SiteWhitelist { get; set; }
        public string SiteBlacklist { get; set; }
        public int MaxPagesPerCategory { get; set; }
        public int MaxItemsPerRun { get; set; }
        public int Concurrency { get; set; }
        public int TimeoutSeconds { get; set; }
        public bool ExcludeCloudDrive { get; set; }
        public int NetdiskExcluded { get; set; }
    }

    public class SourceRow
    {
        public string Key { get; set; }
        public string Name { get; set; }
        public int Type { get; set; }
        public string Api { get; set; }
        public bool Supported { get; set; }
        public string Reason { get; set; }
    }

    public class SourcesResult
    {
        public bool Success { get; set; }
        public string Error { get; set; }
        public int Total { get; set; }
        public int SupportedCount { get; set; }
        public List<SourceRow> Sites { get; set; } = new List<SourceRow>();
        public List<string> Warnings { get; set; } = new List<string>();
    }

    public class ProbeResult
    {
        public bool Success { get; set; }
        public string Error { get; set; }
        public string SiteName { get; set; }
        public int CategoryCount { get; set; }
        public List<string> Categories { get; set; } = new List<string>();
        public int ItemCount { get; set; }
        public List<string> Sample { get; set; } = new List<string>();
    }

    // ───────────────────── 实现 ─────────────────────

    public class TvBoxApi : IService
    {
        public IRequest Request { get; set; }
        public IResponse Response { get; set; }

        private static Plugin P => Plugin.Instance;

        /// <summary>
        /// 共享令牌校验。token 从 DTO 上取（ServiceStack 自动绑定 ?token=），
        /// 不用 Request 属性 —— 实测 Emby 4.9 里那个属性没被注入，永远拿不到值。
        ///
        /// 规则：
        ///   HubToken 为空        → 放行（旧安装兼容）
        ///   token 对得上          → 放行（spider-hub 走这条路）
        ///   token 空或不匹配      → 拒绝
        /// Emby 自己的配置页走 api().getUrl()，我们在 config.html 里给它拼了 token。
        /// </summary>
        private static bool HubOk(string token)
        {
            var cfg = P?.Configuration;
            if (cfg == null) return false;
            if (string.IsNullOrWhiteSpace(cfg.HubToken)) return true;
            var expect = cfg.HubToken.Trim();
            return !string.IsNullOrEmpty(token) && string.Equals(token.Trim(), expect, StringComparison.Ordinal);
        }

        // ── 状态 ──
        public object Get(GetStatus req)
        {
            var p = P;
            if (p == null) return new StatusResult { Stage = "插件未初始化" };
            if (!HubOk(req.Token)) return new StatusResult { Stage = "token 无效" };
            var cfg = p.Configuration;
            var pr = p.SyncService.Progress;
            lock (pr)
            {
                return new StatusResult
                {
                    Running = pr.Running,
                    Stage = pr.Stage,
                    CurrentSite = pr.CurrentSite,
                    SiteIndex = pr.SiteIndex,
                    SiteTotal = pr.SiteTotal,
                    ItemsWritten = pr.ItemsWritten,
                    EpisodesWritten = pr.EpisodesWritten,
                    Errors = pr.Errors,
                    LastError = pr.LastError,
                    OutputRoot = p.ResolveOutputRoot(),
                    LastSyncUtc = cfg.LastSyncUtc == DateTime.MinValue ? "" : cfg.LastSyncUtc.ToString("u"),
                    LastSyncSummary = cfg.LastSyncSummary,
                    ConfigUrl = cfg.ConfigUrl,
                    SiteWhitelist = cfg.SiteWhitelist,
                    SiteBlacklist = cfg.SiteBlacklist,
                    MaxPagesPerCategory = cfg.MaxPagesPerCategory,
                    MaxItemsPerRun = cfg.MaxItemsPerRun,
                    Concurrency = cfg.Concurrency,
                    TimeoutSeconds = cfg.TimeoutSeconds,
                    ExcludeCloudDrive = cfg.ExcludeCloudDrive,
                    NetdiskExcluded = cfg.LastSyncSummary != null && cfg.LastSyncSummary.Contains("网盘") ? 1 : 0,
                };
            }
        }

        // ── 站点列表 ──
        public object Get(GetSources req)
        {
            var p = P;
            if (p == null) return new SourcesResult { Error = "插件未初始化" };
            var cfg = p.Configuration;

            try
            {
                using (var http = SyncService.CreateClient(cfg))
                {
                    var loader = new TvBoxConfigLoader(http);
                    var doc = loader.LoadAsync(cfg.ConfigUrl, CancellationToken.None)
                                    .GetAwaiter().GetResult();
                    TvBoxConfigLoader.ApplySpiderHub(doc, cfg.SpiderHubUrl);

                    var result = new SourcesResult
                    {
                        Success = true,
                        Total = doc.Sites.Count,
                        Warnings = doc.LoadWarnings,
                    };
                    foreach (var s in doc.Sites)
                    {
                        var row = new SourceRow
                        {
                            Key = s.Key,
                            Name = s.DisplayName,
                            Type = s.Type,
                            Api = s.Api,
                            Supported = s.Supported,
                        };
                        if (!s.Supported)
                            row.Reason = s.Type != 0 && s.Type != 1
                                ? $"type={s.Type} 是 spider（jar/js），本插件跑不了"
                                : "api 不是 http(s) 地址";
                        else
                            row.Reason = "可直接同步";
                        result.Sites.Add(row);
                    }
                    result.SupportedCount = result.Sites.Count(x => x.Supported);
                    return result;
                }
            }
            catch (Exception ex)
            {
                return new SourcesResult { Error = ex.Message };
            }
        }

        // ── 手动同步 ──
        public object Post(StartSync req)
        {
            var p = P;
            if (!HubOk(req.Token)) return new SourcesResult { Success = false, Error = "token 无效" };
            if (p == null) return new SimpleResult { Message = "插件未初始化" };
            if (!HubOk(req.Token)) return new SimpleResult { Success = false, Message = "token 无效" };
            if (p.SyncService.IsRunning)
                return new SimpleResult { Success = false, Message = "已有同步在运行" };

            var cfg = p.Configuration;
            if (string.IsNullOrWhiteSpace(cfg.ConfigUrl))
                return new SimpleResult { Success = false, Message = "还没配置 TVBox 配置地址" };

            var outputRoot = p.ResolveOutputRoot();
            var baseUrl = p.ResolveEmbyBaseUrl();

            // 后台跑，立刻返回，避免前端请求超时
            _ = Task.Run(async () =>
            {
                try
                {
                    await p.SyncService.RunAsync(cfg, outputRoot, CancellationToken.None,
                        () => p.SaveConfiguration(), baseUrl).ConfigureAwait(false);
                }
                catch (Exception ex)
                {
                    p.Logger.Error("手动同步失败: " + ex.Message);
                }
            });

            return new SimpleResult { Success = true, Message = "同步已在后台开始，进度看下面的状态" };
        }

        // ── 停止同步 ──
        public object Post(StopSync req)
        {
            var p = P;
            if (p == null) return new SimpleResult { Message = "插件未初始化" };
            if (!p.SyncService.IsRunning)
                return new SimpleResult { Success = false, Message = "当前没有正在运行的同步" };
            var ok = p.SyncService.RequestStop();
            return new SimpleResult
            {
                Success = ok,
                Message = ok ? "已请求停止，当前条目结束后会退出" : "停止请求未生效（可能刚好结束）",
            };
        }

        // ── 读配置（hub 界面镜像用）──
        public object Get(GetConfig req)
        {
            var p = P;
            if (!HubOk(req?.Token)) return new ConfigResult { Success = false, Message = "token 无效" };
            var cfg = p?.Configuration;
            if (cfg == null) return new ConfigResult { Success = false, Message = "插件未初始化" };
            return new ConfigResult
            {
                Success = true,
                ConfigUrl = cfg.ConfigUrl,
                SpiderHubUrl = cfg.SpiderHubUrl,
                OutputRoot = cfg.OutputRoot,
                EmbyBaseUrl = cfg.EmbyBaseUrl,
                SiteWhitelist = cfg.SiteWhitelist,
                SiteBlacklist = cfg.SiteBlacklist,
                SiteNameFilter = cfg.SiteNameFilter,
                MaxPagesPerCategory = cfg.MaxPagesPerCategory,
                MaxItemsPerRun = cfg.MaxItemsPerRun,
                Concurrency = cfg.Concurrency,
                RequestDelayMs = cfg.RequestDelayMs,
                TimeoutSeconds = cfg.TimeoutSeconds,
                UserAgent = cfg.UserAgent,
                Referer = cfg.Referer,
                UseProxy = cfg.UseProxy,
                AllowPrivateHosts = cfg.AllowPrivateHosts,
                CleanupOrphans = cfg.CleanupOrphans,
                ExcludeCloudDrive = cfg.ExcludeCloudDrive,
                EnableScheduledSync = cfg.EnableScheduledSync,
                SyncIntervalHours = cfg.SyncIntervalHours,
                ConfigFilePath = p.ConfigurationFilePath,
                ReadOnly = new[] { "ConfigFilePath" },
            };
        }

        // ── 写配置（hub 界面 + 插件自带配置页共用同一份 TvBox.xml）──
        public object Post(UpdateConfig req)
        {
            var p = P;
            if (!HubOk(req?.Token)) return new SimpleResult { Success = false, Message = "token 无效" };
            var cfg = p?.Configuration;
            if (cfg == null) return new SimpleResult { Success = false, Message = "插件未初始化" };

            int changed = 0;
            if (req.ConfigUrl != null && req.ConfigUrl.Trim() != cfg.ConfigUrl) { cfg.ConfigUrl = req.ConfigUrl.Trim(); changed++; }
            if (req.SpiderHubUrl != null && req.SpiderHubUrl.Trim() != cfg.SpiderHubUrl) { cfg.SpiderHubUrl = req.SpiderHubUrl.Trim(); changed++; }
            if (req.SiteWhitelist != null) { cfg.SiteWhitelist = req.SiteWhitelist; changed++; }
            if (req.SiteBlacklist != null) { cfg.SiteBlacklist = req.SiteBlacklist; changed++; }
            if (req.SiteNameFilter != null) { cfg.SiteNameFilter = req.SiteNameFilter; changed++; }
            if (req.MaxPagesPerCategory.HasValue) { cfg.MaxPagesPerCategory = Math.Clamp(req.MaxPagesPerCategory.Value, 1, 50); changed++; }
            if (req.MaxItemsPerRun.HasValue) { cfg.MaxItemsPerRun = Math.Max(0, req.MaxItemsPerRun.Value); changed++; }
            if (req.Concurrency.HasValue) { cfg.Concurrency = Math.Clamp(req.Concurrency.Value, 1, 16); changed++; }
            if (req.TimeoutSeconds.HasValue) { cfg.TimeoutSeconds = Math.Clamp(req.TimeoutSeconds.Value, 5, 120); changed++; }
            if (req.RequestDelayMs.HasValue) { cfg.RequestDelayMs = Math.Clamp(req.RequestDelayMs.Value, 0, 5000); changed++; }
            if (req.ExcludeCloudDrive.HasValue) { cfg.ExcludeCloudDrive = req.ExcludeCloudDrive.Value; changed++; }
            if (!string.IsNullOrWhiteSpace(req.OutputRoot)) { cfg.OutputRoot = req.OutputRoot.Trim(); changed++; }
            if (req.EmbyBaseUrl != null) { cfg.EmbyBaseUrl = req.EmbyBaseUrl.Trim(); changed++; }
            if (req.Referer != null) { cfg.Referer = req.Referer; changed++; }
            if (!string.IsNullOrWhiteSpace(req.UserAgent)) { cfg.UserAgent = req.UserAgent.Trim(); changed++; }
            if (req.UseProxy.HasValue) { cfg.UseProxy = req.UseProxy.Value; changed++; }
            if (req.AllowPrivateHosts.HasValue) { cfg.AllowPrivateHosts = req.AllowPrivateHosts.Value; changed++; }
            if (req.CleanupOrphans.HasValue) { cfg.CleanupOrphans = req.CleanupOrphans.Value; changed++; }
            if (req.EnableScheduledSync.HasValue) { cfg.EnableScheduledSync = req.EnableScheduledSync.Value; changed++; }
            if (req.SyncIntervalHours.HasValue) { cfg.SyncIntervalHours = Math.Clamp(req.SyncIntervalHours.Value, 1, 168); changed++; }

            try
            {
                p.SaveConfiguration();
            }
            catch (Exception ex)
            {
                return new SimpleResult { Success = false, Message = "保存失败: " + ex.Message };
            }
            return new SimpleResult { Success = true, Message = changed + " 项已保存" };
        }

        // ── 输出目录可写性实测（只有 Emby 自己的身份才能回答这个问题）──
        public object Get(ProbeWrite req)
        {
            var p = P;
            if (!HubOk(req?.Token)) return new WriteProbeResult { Ok = false, Message = "token 无效" };
            if (req == null || string.IsNullOrWhiteSpace(req.Path))
                return new WriteProbeResult { Ok = false, Message = "缺少 Path 参数" };

            var root = req.Path.Trim().TrimEnd('/');
            var targets = new List<string> { root, root + "/movies", root + "/tv" };
            var items = new List<WriteProbeItem>();
            bool allOk = true;

            foreach (var dir in targets)
            {
                var it = new WriteProbeItem { Path = dir };
                try
                {
                    it.Exists = Directory.Exists(dir);
                    if (!it.Exists) Directory.CreateDirectory(dir);   // 不存在就试着建，建不了也算结论
                    var probe = Path.Combine(dir, ".tvbox-write-probe-" + Guid.NewGuid().ToString("N").Substring(0, 8));
                    File.WriteAllText(probe, "probe");
                    File.Delete(probe);
                    it.Writable = true;
                }
                catch (Exception ex)
                {
                    it.Writable = false;
                    it.Error = ex.GetType().Name + ": " + ex.Message;
                    allOk = false;
                }
                items.Add(it);
            }

            string user;
            try { user = System.Security.Principal.WindowsIdentity.GetCurrent().Name; }
            catch { user = Environment.UserName; }

            return new WriteProbeResult
            {
                Ok = allOk,
                User = user + " (uid=" + SafeUid() + ")",
                Message = allOk ? "三个目录都能写" : "有目录写不进去",
                Items = items,
            };
        }

        /// <summary>Linux 下取当前进程 uid，方便确认是不是 Emby 的身份。</summary>
        private static string SafeUid()
        {
            try
            {
                foreach (var line in File.ReadAllLines("/proc/self/status"))
                    if (line.StartsWith("Uid:")) return line.Substring(4).Trim().Split('\t', ' ')[0];
            }
            catch { }
            return "?";
        }

        // ── 单站探测（排错用）──
        public object Get(ProbeSite req)
        {
            var p = P;
            if (!HubOk(req.Token)) return new SimpleResult { Success = false, Message = "token 无效" };
            if (p == null) return new ProbeResult { Error = "插件未初始化" };
            var cfg = p.Configuration;
            var key = req?.SiteKey;
            if (string.IsNullOrWhiteSpace(key)) return new ProbeResult { Error = "缺少 siteKey" };

            try
            {
                using (var http = SyncService.CreateClient(cfg))
                {
                    var loader = new TvBoxConfigLoader(http);
                    var doc = loader.LoadAsync(cfg.ConfigUrl, CancellationToken.None).GetAwaiter().GetResult();
                    var site = doc.Sites.FirstOrDefault(s =>
                        string.Equals(s.Key, key, StringComparison.OrdinalIgnoreCase));
                    if (site == null) return new ProbeResult { Error = "找不到这个站点: " + key };
                    if (!site.Supported) return new ProbeResult { SiteName = site.DisplayName, Error = "该站点类型不支持" };

                    var client = new CmsClient(http, null, cfg.Referer);
                    var cats = client.GetCategoriesAsync(site, CancellationToken.None).GetAwaiter().GetResult();
                    var probe = new ProbeResult
                    {
                        Success = true,
                        SiteName = site.DisplayName,
                        CategoryCount = cats.Count,
                    };
                    probe.Categories = cats.Take(15).Select(c => c.Id + ":" + c.Name).ToList();

                    if (cats.Count > 0)
                    {
                        var list = client.GetListAsync(site, cats[0].Id, 1, CancellationToken.None)
                                         .GetAwaiter().GetResult();
                        probe.ItemCount = list.Items.Count;
                        probe.Sample = list.Items.Take(10).Select(x => x.Id + " " + x.Name).ToList();
                    }
                    return probe;
                }
            }
            catch (Exception ex)
            {
                return new ProbeResult { Error = ex.Message };
            }
        }

        // ── 播放代理 ──
        // 两个关键点（都踩过）：
        //  1) 必须标 [Unauthenticated]。Emby 默认对未标 [Authenticated] 的路由也做 token 校验，
        //     而 Emby 内部 ffmpeg 拉流不会带 token，不加会直接 401。
        //  2) 不能用 Response.OutputWriter 自己写再 return null —— Emby 会当成空响应返回 204。
        //     正确做法是用 IHttpResultFactory.GetResult 返回 Stream 结果。
        public object Get(StreamProxy req)
        {
            try
            {
                return GetCore(req);
            }
            catch (Exception ex)
            {
                try { P?.Logger.Error("StreamProxy 失败: " + ex); } catch { }
                return PlainText(500, "StreamProxy error: " + ex);
            }
        }

        private object GetCore(StreamProxy req)
        {
            var rawUrl = SyncService.Base64UrlDecode(req?.U);
            if (string.IsNullOrWhiteSpace(rawUrl) ||
                !(rawUrl.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                  rawUrl.StartsWith("https://", StringComparison.OrdinalIgnoreCase)))
                return PlainText(400, "invalid url");
            if (IsBlockedHost(rawUrl))
                return PlainText(403, "blocked host");

            var referer = SyncService.Base64UrlDecode(req?.R);
            var cfg = P?.Configuration;

            try
            {
                var handler = new HttpClientHandler
                {
                    AutomaticDecompression = System.Net.DecompressionMethods.GZip | System.Net.DecompressionMethods.Deflate,
                    AllowAutoRedirect = true,
                };
                var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(30) };

                var request = new HttpRequestMessage(HttpMethod.Get, rawUrl);
                if (cfg != null && !string.IsNullOrWhiteSpace(cfg.UserAgent))
                    request.Headers.TryAddWithoutValidation("User-Agent", cfg.UserAgent);
                var refToUse = referer ?? cfg?.Referer;
                if (!string.IsNullOrWhiteSpace(refToUse))
                    request.Headers.TryAddWithoutValidation("Referer", refToUse);

                var resp = http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead)
                               .GetAwaiter().GetResult();

                var contentType = resp.Content.Headers.ContentType?.ToString() ?? "application/octet-stream";
                var isPlaylist = contentType.IndexOf("mpegurl", StringComparison.OrdinalIgnoreCase) >= 0
                                 || rawUrl.Contains(".m3u8", StringComparison.OrdinalIgnoreCase);

                if (isPlaylist)
                {
                    var text = resp.Content.ReadAsStringAsync().GetAwaiter().GetResult();
                    var rewritten = RewritePlaylist(text, rawUrl);
                    var bytes = Encoding.UTF8.GetBytes(rewritten);
                    resp.Dispose();
                    http.Dispose();
                    return Factory().GetResult(Request, new MemoryStream(bytes),
                        "application/vnd.apple.mpegurl", null);
                }

                var upstream = resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult();
                // Stream 的生命周期交给 Emby，Dispose 时连带释放 response / client
                return Factory().GetResult(Request,
                    new ResponseOwnedStream(upstream, resp, http), contentType, null);
            }
            catch (Exception ex)
            {
                return PlainText(502, "upstream error: " + ex.Message);
            }
        }

        /// <summary>把原始地址包装成带扩展名的代理路径，例如 /TvBox/Stream/seg0.ts?u=...</summary>
        public static string BuildProxyPath(string originalUrl, string referer = null)
        {
            string name = "stream";
            try
            {
                var u = new Uri(originalUrl);
                name = Path.GetFileName(u.AbsolutePath);
                if (string.IsNullOrWhiteSpace(name)) name = "stream";
                foreach (var c in Path.GetInvalidFileNameChars()) name = name.Replace(c, '_');
                if (name.Length > 64) name = name.Substring(name.Length - 64);
            }
            catch { }
            var path = "/TvBox/Stream/" + name + "?u=" + SyncService.Base64UrlEncode(originalUrl);
            if (!string.IsNullOrWhiteSpace(referer))
                path += "&r=" + SyncService.Base64UrlEncode(referer);
            return path;
        }

        private static IHttpResultFactory Factory()
        {
            var host = P?.ApplicationHost;
            if (host == null) throw new InvalidOperationException("插件未初始化");
            return host.Resolve<IHttpResultFactory>();
        }

        private object PlainText(int status, string text)
        {
            try { if (Response != null) Response.StatusCode = status; } catch { }
            try
            {
                var built = Factory().GetResult(Request,
                    new MemoryStream(Encoding.UTF8.GetBytes(text)), "text/plain; charset=utf-8", null);
                // ⚠️ Response / Request 这两个属性在 Emby 4.9 里都没被注入（同 HubOk 那个坑），
                // 所以靠 Response.StatusCode 设状态码是无效的 —— 实测所有错误都返回 200。
                // 改成设到工厂返回的 IHttpResult 上，这个才是真正被写回响应的对象。
                if (built is MediaBrowser.Model.Services.IHttpResult hr)
                {
                    try { hr.StatusCode = (System.Net.HttpStatusCode)status; } catch { }
                }
                return built;
            }
            catch (Exception ex)
            {
                // 连 IHttpResultFactory 都拿不到时，退回直接写 OutputWriter
                try
                {
                    var w = Response?.OutputWriter;
                    if (w != null)
                    {
                        var b = Encoding.UTF8.GetBytes(text + "\n[factory error] " + ex);
                        w.WriteAsync(new ReadOnlyMemory<byte>(b)).GetAwaiter().GetResult();
                        w.FlushAsync().GetAwaiter().GetResult();
                        Response.CompleteAsync().GetAwaiter().GetResult();
                    }
                }
                catch { }
                return null;
            }
        }

        /// <summary>把上游响应包成可读流，读完连同 HttpClient 一起释放。</summary>
        private sealed class ResponseOwnedStream : Stream
        {
            private readonly Stream _inner;
            private readonly IDisposable[] _owned;

            public ResponseOwnedStream(Stream inner, params IDisposable[] owned)
            {
                _inner = inner;
                _owned = owned;
            }

            public override bool CanRead => true;
            public override bool CanSeek => false;
            public override bool CanWrite => false;
            public override long Length => throw new NotSupportedException();
            public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
            public override void Flush() { }
            public override int Read(byte[] buffer, int offset, int count) => _inner.Read(buffer, offset, count);
            public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
            public override void SetLength(long value) => throw new NotSupportedException();
            public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

            protected override void Dispose(bool disposing)
            {
                if (disposing)
                {
                    try { _inner.Dispose(); } catch { }
                    foreach (var o in _owned) { try { o.Dispose(); } catch { } }
                }
                base.Dispose(disposing);
            }
        }

        /// <summary>
        /// 把 HLS 播放列表里的分片/子列表地址改写成走本代理，
        /// 否则客户端拿到原始地址后依然会 403（防盗链）。
        /// </summary>
        private static string RewritePlaylist(string text, string playlistUrl)
        {
            var sb = new StringBuilder(text.Length + 256);
            Uri baseUri = null;
            try { baseUri = new Uri(playlistUrl); } catch { }

            foreach (var rawLine in text.Split('\n'))
            {
                var line = rawLine.TrimEnd('\r');
                if (line.Length == 0 || line.StartsWith("#"))
                {
                    if (line.StartsWith("#EXT-X-KEY") || line.StartsWith("#EXT-X-MAP"))
                        sb.Append(RewriteUriAttribute(line, baseUri)).Append('\n');
                    else
                        sb.Append(line).Append('\n');
                    continue;
                }

                var abs = ToAbsolute(line, baseUri);
                if (abs == null) { sb.Append(line).Append('\n'); continue; }

                // 有些源的播放列表里混了 .png/.jpg 广告图，ffmpeg 的 HLS 解复用器
                // 遇到不在 allowed_segment_extensions 里的条目会直接拒掉整个列表
                //（"Error opening input: Invalid data found when processing input"）。
                // 这些图本来也不是视频分片，直接丢掉。
                if (IsNonMediaSegment(abs)) continue;

                sb.Append(BuildProxyPath(abs)).Append('\n');
            }
            return sb.ToString();
        }

        /// <summary>播放列表里出现的图片/字体等非媒体条目，丢了不影响播放。</summary>
        private static bool IsNonMediaSegment(string url)
        {
            try
            {
                var path = new Uri(url).AbsolutePath;
                var dot = path.LastIndexOf('.');
                if (dot < 0) return false;
                var ext = path.Substring(dot + 1).ToLowerInvariant();
                switch (ext)
                {
                    case "png": case "jpg": case "jpeg": case "gif":
                    case "webp": case "bmp": case "svg": case "ico":
                    case "image": case "avif": case "heic":
                    case "css": case "js": case "html": case "htm": case "json":
                        return true;
                    default:
                        return false;
                }
            }
            catch { return false; }
        }

        private static string RewriteUriAttribute(string line, Uri baseUri)
        {
            var idx = line.IndexOf("URI=\"", StringComparison.Ordinal);
            if (idx < 0) return line;
            var start = idx + 5;
            var end = line.IndexOf('"', start);
            if (end < 0) return line;
            var uri = line.Substring(start, end - start);
            var abs = ToAbsolute(uri, baseUri);
            if (abs == null) return line;
            return line.Substring(0, start) + BuildProxyPath(abs) + line.Substring(end);
        }

        private static string ToAbsolute(string url, Uri baseUri)
        {
            try
            {
                if (url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                    url.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                    return url;
                if (baseUri == null) return null;
                return new Uri(baseUri, url).ToString();
            }
            catch { return null; }
        }

        /// <summary>
        /// SSRF 防护。这里分成两档：
        ///   档一（永久拦截，AllowPrivateHosts 也放不开）：localhost / 127.0.0.0/8 /
        ///         ::1 / 169.254.0.0/16（含云元数据 169.254.169.254）/ 0.0.0.0。
        ///         /TvBox/Stream 是免登录的（Emby 播放 strm 不带 token），
        ///         放开这一档等于给任何人一个打本机、打内网元数据的代理。
        ///   档二（可配放行）：10/8、172.16/12、192.168/16 —— TVBox 有些源就在内网。
        /// </summary>
        private bool IsBlockedHost(string url)
        {
            bool allowPrivate = P?.Configuration?.AllowPrivateHosts == true;
            try
            {
                var u = new Uri(url);
                var host = u.Host;
                if (host.Equals("localhost", StringComparison.OrdinalIgnoreCase)) return true;
                if (host.Equals("localhost.localdomain", StringComparison.OrdinalIgnoreCase)) return true;

                if (System.Net.IPAddress.TryParse(host, out var ip))
                {
                    if (System.Net.IPAddress.IsLoopback(ip)) return true;          // 127/8、::1
                    if (ip.Equals(System.Net.IPAddress.Any) || ip.Equals(System.Net.IPAddress.IPv6Any)) return true;

                    var b = ip.GetAddressBytes();
                    if (b.Length == 4)
                    {
                        if (b[0] == 0) return true;
                        if (b[0] == 169 && b[1] == 254) return true;               // link-local + 云元数据
                        if (!allowPrivate)
                        {
                            if (b[0] == 10) return true;
                            if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) return true;
                            if (b[0] == 192 && b[1] == 168) return true;
                        }
                        return false;
                    }
                    // IPv6：fe80::/10 link-local、fc00::/7 ULA
                    if ((b[0] & 0xFE) == 0xFC) return !allowPrivate;
                    if (b[0] == 0xFE && (b[1] & 0xC0) == 0x80) return true;
                    return false;
                }
                return false;
            }
            catch { return true; }
        }

    }
}
