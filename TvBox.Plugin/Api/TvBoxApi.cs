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

    /// <summary>
    /// 插件自更新：从 hub 拉一份新的 TvBox.dll，原子替换磁盘上的自己，再让 Emby 重启加载。
    ///
    /// 为什么要有这条：容器部署时 hub 看不到 /config，Emby 4.9 又没有「从 URL 装 DLL」的 API
    /// （PackageService 只能装官方插件目录里的包，且不支持自定义仓库），
    /// 所以升级只能靠插件自己——它以 Emby 用户身份运行，对 plugins/ 本来就有写权限。
    /// </summary>
    [Unauthenticated]
    [Route("/TvBox/SelfUpdate", "GET", Summary = "从 hub 拉取新插件并自我更新")]
    public class SelfUpdate : IReturn<SelfUpdateResult>
    {
        public string Token { get; set; }
        /// <summary>是否让 Emby 自己重启（默认 true）。测试时传 0 可只替换不重启。</summary>
        public int Restart { get; set; } = 1;
        /// <summary>哈希相同也强制重写一遍（验证写入链路用）。</summary>
        public int Force { get; set; }
    }

    public class SelfUpdateResult
    {
        public bool Ok { get; set; }
        public string Stage { get; set; }
        public string Msg { get; set; }
        public bool Updated { get; set; }
        public string LocalPath { get; set; }
        public string LocalSha { get; set; }
        public string RemoteSha { get; set; }
        public long RemoteBytes { get; set; }
        public bool Restarting { get; set; }
        public bool CanSelfRestart { get; set; }
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
        // ── 输出内容 / 探测 / 播放策略（以前只在插件页能改，hub 界面传了也会被丢掉）──
        public bool? WriteNfo { get; set; }
        public bool? DownloadPoster { get; set; }
        public int? PosterMaxMb { get; set; }
        public bool? VerifyPlayUrl { get; set; }
        public int? VerifyMaxEpisodes { get; set; }
        public int? DetailBatchSize { get; set; }
        public bool? ResolveIndirectUrl { get; set; }
        public string StreamMode { get; set; }
        public string RedirectHosts { get; set; }
        public bool? IncrementalSync { get; set; }
        public int? IncrementalHours { get; set; }
        public int? MaxDeletePerRun { get; set; }
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
        public bool VerifyPlayUrl { get; set; }
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
        // 下面这些以前没回给 hub，导致 hub 界面显示不出来、也存不进去
        public bool WriteNfo { get; set; }
        public bool DownloadPoster { get; set; }
        public int PosterMaxMb { get; set; }
        public int VerifyMaxEpisodes { get; set; }
        public int DetailBatchSize { get; set; }
        public bool ResolveIndirectUrl { get; set; }
        public string StreamMode { get; set; }
        public string RedirectHosts { get; set; }
        public bool IncrementalSync { get; set; }
        public int IncrementalHours { get; set; }
        public int MaxDeletePerRun { get; set; }
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

    [Unauthenticated]   // hub 用 ?token= 调，靠 HubOk 校验；不加这个 Emby 会先拦 401
    [Route("/TvBox/PushItem", "POST", Summary = "把某站点的一条内容立刻写进 Emby 库（搜索页一键推送）")]
    public class PushItem : IReturn<PushItemResult>
    {
        public string Token { get; set; }
        public string Site { get; set; }
        public string Id { get; set; }
    }

    public class PushItemResult
    {
        public bool Ok { get; set; }
        public string Msg { get; set; }
        public string Name { get; set; }
        public bool Series { get; set; }
        public int Count { get; set; }
        public List<string> Paths { get; set; }
    }

    [Unauthenticated]
    [Route("/TvBox/Live", "GET", Summary = "直播源：把 hub /live/<站点> 的 M3U 转出来，Emby Live TV 直接填这个地址")]
    public class LiveProxy : IReturnVoid
    {
        /// <summary>站点 key，如「网络直播」。不填则返回所有同步站点合并后的列表。</summary>
        public string Key { get; set; }
    }

    [Unauthenticated]   // 播放器/Emby 校验都不会带 token
    [Route("/TvBox/Play", "GET,HEAD", Summary = "把 spider 的私有分集 id 解析成真地址（内部走 hub /play/）")]
    public class PlayResolve : IReturnVoid
    {
        /// <summary>站点 key（hub 里的站点标识）。</summary>
        public string Site { get; set; }
        /// <summary>线路名（vod_play_from，如「在线播放」「高清」）。</summary>
        public string Flag { get; set; }
        /// <summary>spider 的私有分集 id（如 146660@1314815）。</summary>
        public string Id { get; set; }
        public string Token { get; set; }
    }

    [Unauthenticated]   // 播放器/ffmpeg 拉分片不会带 Emby token，缺这个整条播放链 401
    [Route("/TvBox/Stream/{Name}", "GET,HEAD", Summary = "播放地址代理（补 Referer / UA，并重写 HLS 分片地址）")]
    [Route("/TvBox/Stream", "GET,HEAD", Summary = "播放地址代理（兼容无扩展名写法）")]
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

        /// <summary>
        /// playerContent 给的 header（JSON，base64url）。CDN 常要求特定 Referer/UA/Cookie，
        /// 没有它整条播放链 401/403。分片重写时也要带着走。
        /// </summary>
        public string H { get; set; }
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

        /// <summary>当前站点的 Referer，Play 代理时补上（很多 CDN 缺它就 403）。</summary>
        private static string _lastPlayReferer = null;

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
            // token 没配时只允许只读接口通过（否则界面自检会全废），
            // 写/换代码类接口一律走 HubOkStrict。
            if (string.IsNullOrWhiteSpace(cfg.HubToken)) return true;
            return TokenEquals(token, cfg.HubToken);
        }

        /// <summary>
        /// 严格版：没配 token 直接拒绝（fail-closed）。
        /// 用在会改磁盘、写媒体库、换 DLL 的接口上 —— 以前这里 fail-open，
        /// 新装环境等于所有人都能换插件 DLL（以 Emby 用户身份 RCE）。
        /// </summary>
        private static bool HubOkStrict(string token)
        {
            var cfg = P?.Configuration;
            if (cfg == null) return false;
            if (string.IsNullOrWhiteSpace(cfg.HubToken)) return false;
            return TokenEquals(token, cfg.HubToken);
        }

        private static bool TokenEquals(string given, string expect)
        {
            if (string.IsNullOrEmpty(given)) return false;
            var a = System.Text.Encoding.UTF8.GetBytes(given.Trim());
            var b = System.Text.Encoding.UTF8.GetBytes(expect.Trim());
            if (a.Length != b.Length) return false;
            return System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(a, b);
        }

        // ── 插件自更新 ──
        public SelfUpdateResult Get(SelfUpdate req)
        {
            var r = new SelfUpdateResult { Stage = "检查" };
            if (!HubOk(req?.Token)) { r.Msg = "token 无效"; return r; }
            var cfg = P?.Configuration;
            var hub = (cfg?.SpiderHubUrl ?? "").Trim().TrimEnd('/');
            if (string.IsNullOrWhiteSpace(hub)) { r.Msg = "插件里没配 hub 地址"; return r; }

            // Emby 用 Assembly.Load(byte[]) 加载插件，Assembly.Location 是空串，
            // 所以只能从宿主的 IApplicationPaths.PluginsPath 拼出自己的 DLL 路径。
            string localPath = ResolvePluginDllPath();
            if (localPath == null)
            { r.Msg = "定位不到插件自身的 TvBox.dll（plugins 目录里没找到）"; return r; }
            r.LocalPath = localPath;
            r.LocalSha = Sha256OfFile(localPath);

            byte[] bytes;
            string remoteSha;
            try
            {
                using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(3) };
                using var resp = http.GetAsync(hub + "/api/plugin/dll").GetAwaiter().GetResult();
                if (!resp.IsSuccessStatusCode)
                { r.Msg = "hub 返回 " + (int)resp.StatusCode + "，拿不到新插件"; return r; }
                bytes = resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult();
                remoteSha = resp.Headers.TryGetValues("X-Plugin-Sha256", out var vs) ? vs.FirstOrDefault() : null;
            }
            catch (Exception ex) { r.Msg = "下载失败: " + ex.Message; return r; }

            if (bytes == null || bytes.Length < 10240)
            { r.Msg = "hub 给的文件不像插件（" + (bytes?.Length ?? 0) + " 字节）"; return r; }
            r.RemoteBytes = bytes.Length;
            r.RemoteSha = remoteSha ?? Sha256OfBytes(bytes);

            if (req.Force == 0 && string.Equals(r.RemoteSha, r.LocalSha, StringComparison.OrdinalIgnoreCase))
            {
                r.Ok = true;
                r.Stage = "完成";
                r.Msg = "已是最新版本（" + r.LocalSha.Substring(0, Math.Min(12, r.LocalSha.Length)) + "）";
                return r;
            }

            try
            {
                r.Stage = "写入";
                var dir = Path.GetDirectoryName(localPath);
                var tmp = Path.Combine(dir, Path.GetFileName(localPath) + ".tmp-" + DateTime.UtcNow.Ticks);
                File.WriteAllBytes(tmp, bytes);
                // 原子替换：正在跑的 Emby 映射着老 inode，直接覆盖会 SIGBUS。
                File.Move(tmp, localPath, true);
                var after = Sha256OfFile(localPath);
                if (!string.Equals(after, r.RemoteSha, StringComparison.OrdinalIgnoreCase))
                { r.Msg = "写入后校验不一致（" + after + " ≠ " + r.RemoteSha + "）"; return r; }
                r.Updated = true;
                r.LocalSha = after;
            }
            catch (Exception ex) { r.Msg = "写入失败: " + ex.Message; return r; }

            var host = P?.ApplicationHost;
            r.CanSelfRestart = host != null && host.CanSelfRestart;
            if (req.Restart != 0 && host != null)
            {
                try
                {
                    if (host.CanSelfRestart)
                    {
                        r.Restarting = true;
                        r.Ok = true;
                        r.Stage = "重启";
                        r.Msg = "已更新到 " + r.RemoteSha.Substring(0, Math.Min(12, r.RemoteSha.Length))
                                + "，正在重启 Emby 加载新版本";
                        // 先把响应回出去再重启：重启会让本次请求断开，客户端会以为是失败
                        Task.Run(async () =>
                        {
                            await Task.Delay(1500).ConfigureAwait(false);
                            try { host.Restart(); } catch { }
                        });
                        return r;
                    }
                    host.NotifyPendingRestart();
                }
                catch { }
            }
            r.Ok = true;
            r.Stage = "完成";
            r.Msg = "已更新到 " + r.RemoteSha.Substring(0, Math.Min(12, r.RemoteSha.Length))
                    + (req.Restart == 0 ? "（按要求未重启）" : "；Emby 不能自重启，请手动重启后生效");
            return r;
        }

        /// <summary>在 plugins 目录里找出自己的 DLL：先用固定名，再按大小写不敏感扫一遍。</summary>
        private static string ResolvePluginDllPath()
        {
            try
            {
                var dir = P?.PluginsPath;
                if (string.IsNullOrWhiteSpace(dir) || !Directory.Exists(dir)) return null;
                var direct = Path.Combine(dir, "TvBox.dll");
                if (File.Exists(direct)) return direct;
                foreach (var f in Directory.GetFiles(dir, "*.dll"))
                    if (string.Equals(Path.GetFileName(f), "TvBox.dll", StringComparison.OrdinalIgnoreCase))
                        return f;
            }
            catch { }
            return null;
        }

        private static string Sha256OfFile(string path)
        {
            try
            {
                using var fs = File.OpenRead(path);
                using var sha = System.Security.Cryptography.SHA256.Create();
                return Convert.ToHexString(sha.ComputeHash(fs)).ToLowerInvariant();
            }
            catch { return ""; }
        }

        private static string Sha256OfBytes(byte[] b)
        {
            using var sha = System.Security.Cryptography.SHA256.Create();
            return Convert.ToHexString(sha.ComputeHash(b)).ToLowerInvariant();
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
            // 以前这里没校验：任何人都能反复打断正在跑的同步（DoS），
            // 还会把中断算成「站点失败」污染死站退避。
            if (!HubOk(req?.Token)) return new SimpleResult { Success = false, Message = "token 无效" };
            if (!p.SyncService.IsRunning)
                return new SimpleResult { Success = false, Message = "当前没有正在运行的同步" };
            var ok = p.SyncService.RequestStop();
            return new SimpleResult
            {
                Success = ok,
                Message = ok ? "已请求停止，当前条目结束后会退出" : "已请求停止（最迟等当前这次 spider 调用返回，约 45 秒内退出）",
            };
        }

        // ── 读配置（hub 界面镜像用）──
        public object Get(GetConfig req)
        {
            var p = P;
            if (!HubOkStrict(req?.Token)) return new ConfigResult { Success = false, Message = "token 无效或未配置（拒绝改配置）" };
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
                VerifyPlayUrl = cfg.VerifyPlayUrl,
                EnableScheduledSync = cfg.EnableScheduledSync,
                SyncIntervalHours = cfg.SyncIntervalHours,
                WriteNfo = cfg.WriteNfo,
                DownloadPoster = cfg.DownloadPoster,
                PosterMaxMb = cfg.PosterMaxMb,
                VerifyMaxEpisodes = cfg.VerifyMaxEpisodes,
                DetailBatchSize = cfg.DetailBatchSize,
                ResolveIndirectUrl = cfg.ResolveIndirectUrl,
                StreamMode = cfg.StreamMode,
                RedirectHosts = cfg.RedirectHosts,
                IncrementalSync = cfg.IncrementalSync,
                IncrementalHours = cfg.IncrementalHours,
                MaxDeletePerRun = cfg.MaxDeletePerRun,
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
            // 输出内容 / 探测 / 播放策略
            if (req.WriteNfo.HasValue) { cfg.WriteNfo = req.WriteNfo.Value; changed++; }
            if (req.DownloadPoster.HasValue) { cfg.DownloadPoster = req.DownloadPoster.Value; changed++; }
            if (req.PosterMaxMb.HasValue) { cfg.PosterMaxMb = Math.Clamp(req.PosterMaxMb.Value, 1, 50); changed++; }
            if (req.VerifyPlayUrl.HasValue) { cfg.VerifyPlayUrl = req.VerifyPlayUrl.Value; changed++; }
            if (req.VerifyMaxEpisodes.HasValue) { cfg.VerifyMaxEpisodes = Math.Clamp(req.VerifyMaxEpisodes.Value, 1, 1000); changed++; }
            if (req.DetailBatchSize.HasValue) { cfg.DetailBatchSize = Math.Clamp(req.DetailBatchSize.Value, 1, 200); changed++; }
            if (req.ResolveIndirectUrl.HasValue) { cfg.ResolveIndirectUrl = req.ResolveIndirectUrl.Value; changed++; }
            if (!string.IsNullOrWhiteSpace(req.StreamMode))
            {
                var sm = req.StreamMode.Trim().ToLowerInvariant();
                if (sm == "proxy" || sm == "redirect" || sm == "auto") { cfg.StreamMode = sm; changed++; }
            }
            if (req.RedirectHosts != null) { cfg.RedirectHosts = req.RedirectHosts.Trim(); changed++; }
            if (req.IncrementalSync.HasValue) { cfg.IncrementalSync = req.IncrementalSync.Value; changed++; }
            if (req.IncrementalHours.HasValue) { cfg.IncrementalHours = Math.Clamp(req.IncrementalHours.Value, 0, 720); changed++; }
            if (req.MaxDeletePerRun.HasValue) { cfg.MaxDeletePerRun = Math.Clamp(req.MaxDeletePerRun.Value, 0, 100000); changed++; }

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
            if (!HubOkStrict(req?.Token)) return new WriteProbeResult { Ok = false, Message = "token 无效或未配置（拒绝写探针）" };
            // 以前 Path 完全不做校验：传 /tmp/evil 就能以 Emby 身份建任意目录树、写文件、再删掉。
            // 现在只允许在插件自己的输出目录（OutputRoot）里做写入探测。
            var rootFull = SafeFullPath(p?.ResolveOutputRoot());
            var reqFull = SafeFullPath(req?.Path);
            if (rootFull == null || reqFull == null ||
                !(reqFull.Equals(rootFull, StringComparison.Ordinal) ||
                  reqFull.StartsWith(rootFull.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar,
                                    StringComparison.OrdinalIgnoreCase)))
            {
                return new WriteProbeResult
                {
                    Ok = false,
                    Message = "只允许探测输出目录（" + (rootFull ?? "未配置") + "）下的路径，已拒绝: " + (req?.Path ?? ""),
                };
            }
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
        /// <summary>
        /// 解析私有分集 id → 真播放地址，然后 302 给客户端。
        ///
        /// 为什么不让客户端直接打 hub：hub 的 TCP 只监听回环（安全审计的结论），
        /// 局域网客户端连不上；Emby 本来就在局域网可达，所以由它代跳一次。
        /// hub 内部调 Spider.playerContent(flag, id) —— TVBox 客户端播放前做的同一件事。
        /// </summary>
        /// <summary>单条推送：搜索页点「推送」时调这里。</summary>
        public async Task<object> Post(PushItem req)
        {
            var p = P;
            if (!HubOkStrict(req?.Token)) return new PushItemResult { Ok = false, Msg = "token 无效或未配置（拒绝写库）" };
            var cfg = p.Configuration;
            var root = p.ResolveOutputRoot();
            try
            {
                var r = await p.SyncService.PushOneAsync(cfg, req.Site, req.Id, root,
                                                         p.ResolveEmbyBaseUrl(), CancellationToken.None)
                                             .ConfigureAwait(false);
                return new PushItemResult
                {
                    Ok = r.TryGetValue("ok", out var ok) && ok is bool b && b,
                    Msg = r.TryGetValue("msg", out var m) ? m as string : null,
                    Name = r.TryGetValue("name", out var n) ? n as string : null,
                    Series = r.TryGetValue("series", out var sr) && sr is bool sb && sb,
                    Count = r.TryGetValue("count", out var c) && c is int ci ? ci : 0,
                    Paths = r.TryGetValue("paths", out var ps) ? ps as List<string> : null,
                };
            }
            catch (Exception ex)
            {
                try { p.Logger.Error("[push] 失败: " + ex); } catch { }
                return new PushItemResult { Ok = false, Msg = "推送失败: " + ex.Message };
            }
        }

        /// <summary>
        /// 直播：hub 的 /live/&lt;key&gt; 已经产出标准 M3U，这里原样转出去（顺带处理 key 省略时合并多站）。
        /// Emby 的「Live TV → 添加 M3U tuner」填 http://&lt;lan&gt;:8096/TvBox/Live?key=网络直播 即可。
        /// </summary>
        /// <summary>
        /// 同步取一个 URL 的正文。
        ///
        /// 全部改用 HttpClient 的**同步** Send/ReadAsStream，不再用
        /// GetStringAsync(...).GetAwaiter().GetResult()：那是"同步等异步"，
        /// 每个请求都会占住一个 Emby 线程去等续体。同步时（几百次校验 + 分片拉取）
        /// 线程池被抽干 → Emby 整个 Web 服务失去响应，连"停止同步"都排不进去。
        /// 同步 API 走纯阻塞 IO，不依赖线程池续体。
        /// </summary>
        internal static string HttpGetTextSync(HttpClient http, string url, int timeoutSeconds = 90)
        {
            using (var req = new HttpRequestMessage(HttpMethod.Get, url))
            using (var cts = new System.Threading.CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds)))
            using (var resp = http.Send(req, HttpCompletionOption.ResponseContentRead, cts.Token))
            {
                resp.EnsureSuccessStatusCode();
                using (var st = resp.Content.ReadAsStream())
                using (var sr = new System.IO.StreamReader(st, System.Text.Encoding.UTF8))
                    return sr.ReadToEnd();
            }
        }

        public object Any(LiveProxy req)
        {
            var cfg = P?.Configuration;
            var hub = (cfg?.SpiderHubUrl ?? string.Empty).Trim().TrimEnd('/');
            if (hub.Length == 0) return PlainText(400, "插件里没配 hub 地址");
            try
            {
                var keys = new List<string>();
                if (!string.IsNullOrWhiteSpace(req?.Key)) keys.Add(req.Key.Trim());
                else
                {
                    // 同步名单在 hub 侧（hub.json），问它要
                    try
                    {
                        using var hc = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
                        var js = HttpGetTextSync(hc, hub + "/api/sync-sites", 20);
                        using var doc = System.Text.Json.JsonDocument.Parse(js);
                        if (doc.RootElement.TryGetProperty("sites", out var arr) &&
                            arr.ValueKind == System.Text.Json.JsonValueKind.Array)
                            foreach (var el in arr.EnumerateArray())
                                if (el.GetString() is string k2 && k2.Trim().Length > 0) keys.Add(k2.Trim());
                    }
                    catch { }
                }
                var sb = new System.Text.StringBuilder("#EXTM3U\n");
                int added = 0;
                using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(90) };
                foreach (var key in keys)
                {
                    try
                    {
                        var body = HttpGetTextSync(http, hub + "/live/" + Uri.EscapeDataString(key), 90);
                        if (string.IsNullOrWhiteSpace(body) || !body.Contains("#EXTINF")) continue;
                        foreach (var line in body.Split('\n'))
                        {
                            if (line.StartsWith("#EXTINF")) { sb.Append(line.Replace("group-title=\"", "group-title=\"" + key + "·")); }
                            else if (line.StartsWith("http")) { sb.Append(line); added++; }
                            sb.Append('\n');
                        }
                    }
                    catch (Exception ex)
                    {
                        try { P?.Logger.Debug("[live] " + key + ": " + ex.Message); } catch { }
                    }
                }
                if (added == 0) return PlainText(404, "没有可用的直播源（先确认站点有直播数据）");
                return new LiveResult { Body = sb.ToString() };
            }
            catch (Exception ex)
            {
                return PlainText(500, "直播聚合失败: " + ex.Message);
            }
        }

        /// <summary>直接把 M3U 文本写回去（Emby 的 Live TV 拉的是裸列表）。</summary>
        public class LiveResult
        {
            public string Body { get; set; }
        }

        public object Any(PlayResolve req)
        {
            var cfg = P?.Configuration;
            var hub = (cfg?.SpiderHubUrl ?? string.Empty).Trim().TrimEnd('/');
            if (hub.Length == 0) return PlainText(400, "插件里没配 hub 地址");
            if (string.IsNullOrWhiteSpace(req?.Id)) return PlainText(400, "缺少 id");

            try
            {
                var url = hub + "/play/" + Uri.EscapeDataString(req.Site ?? string.Empty)
                        + "?flag=" + Uri.EscapeDataString(req.Flag ?? string.Empty)
                        + "&id=" + Uri.EscapeDataString(req.Id) + "&json=1";
                string body;
                using (var http = new HttpClient { Timeout = TimeSpan.FromSeconds(45) })
                    body = HttpGetTextSync(http, url, 90);

                bool ok = false; string real = null; string msg = null; string parse = null;
                using (var doc = System.Text.Json.JsonDocument.Parse(body))
                {
                    var root = doc.RootElement;
                    if (root.TryGetProperty("ok", out var okEl))
                        ok = okEl.ValueKind == System.Text.Json.JsonValueKind.True;
                    if (root.TryGetProperty("url", out var uEl)) real = uEl.GetString();
                    if (root.TryGetProperty("msg", out var mEl)) msg = mEl.GetString();
                    if (root.TryGetProperty("parse", out var pEl)) parse = pEl.GetString();
                }
                if (!ok || string.IsNullOrWhiteSpace(real))
                {
                    P?.Logger.Info("[play] 解析失败 site=" + req.Site + " id=" + req.Id + " msg=" + msg);
                    return PlainText(502, "解析失败: " + (msg ?? "hub 没给出地址"));
                }
                P?.Logger.Info("[play] " + req.Site + " " + req.Id + " → " + real + " (parse=" + parse + ")");

                // 不走 302：Emby 的 IHttpResult.StatusCode 实测设不进响应（客户端拿到的是 200 + Location），
                // 播放器和校验都会把空 body 当成 text/plain 判死。直接复用 Stream 的代理逻辑，
                // 顺带还能补 Referer、重写 HLS 分片地址。
                var proxyReq = new StreamProxy
                {
                    U = SyncService.Base64UrlEncode(real),
                    R = SyncService.Base64UrlEncode(_lastPlayReferer),
                };
                return GetCore(proxyReq);
            }
            catch (Exception ex)
            {
                try { P?.Logger.Error("[play] 异常: " + ex.Message); } catch { }
                return PlainText(500, "解析异常: " + ex.Message);
            }
        }

        public object Any(StreamProxy req)
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

        /// <summary>判断 URL 的 host 是否在逗号分隔的域名清单里（支持子域后缀匹配）。</summary>
        private static bool HostInList(string url, string list)
        {
            if (string.IsNullOrWhiteSpace(url) || string.IsNullOrWhiteSpace(list)) return false;
            try
            {
                var host = new Uri(url).Host;
                foreach (var raw in list.Split(new[] { ',', '，', ';', '；', ' ' }, StringSplitOptions.RemoveEmptyEntries))
                {
                    var d = raw.Trim().TrimStart('.').ToLowerInvariant();
                    if (d.Length == 0) continue;
                    var h = host.ToLowerInvariant();
                    if (h == d || h.EndsWith("." + d, StringComparison.Ordinal)) return true;
                }
            }
            catch { }
            return false;
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

            // ── 302 直链模式 ──
            // proxy（默认）：全部流量经 NAS 中转，能补 Referer/UA、能重写 HLS 分片地址。
            // redirect：直接 302 到源站，NAS 只做跳转不转发字节（省带宽/CPU）。
            // auto：按 RedirectHosts 白名单决定，但 m3u8 一律走代理 —— 分片地址里
            //       通常还要补同样的防盗链头，302 出去后客户端会裸连失败。
            var customHeaders = ParseHeaderB64(req?.H);
            var streamMode = (cfg?.StreamMode ?? "proxy").Trim().ToLowerInvariant();
            // 带自定义 header 时不能 302：客户端跳过去会丢掉这些头
            if (customHeaders != null && customHeaders.Count > 0) streamMode = "proxy";
            if (streamMode == "redirect" || streamMode == "auto")
            {
                var isM3u8 = rawUrl.IndexOf(".m3u8", StringComparison.OrdinalIgnoreCase) >= 0;
                var inList = HostInList(rawUrl, cfg?.RedirectHosts);
                var allow302 = streamMode == "redirect" || inList;
                if (allow302 && !isM3u8)
                {
                    var redirect = Redirect302(rawUrl);
                    if (redirect != null) return redirect;
                    // 302 构造失败就照常走代理（绝不返回坏响应）
                }
            }

            try
            {
                var handler = new HttpClientHandler
                {
                    AutomaticDecompression = System.Net.DecompressionMethods.GZip | System.Net.DecompressionMethods.Deflate,
                    // 关掉自动跳转：否则「公网地址 302 到 127.0.0.1」就绕过了 IsBlockedHost。
                    // 下面手动跟最多 3 跳，每一跳都重新做一次 host 校验。
                    AllowAutoRedirect = false,
                };
                var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(30) };

                // 有些播放器（VidHub / AVPlayer）先 HEAD 探测、并对分片发 Range 请求，
                // 不转发 Range 会退化成整段下载，客户端还可能直接判定不支持 seek。
                string rangeHeader = null;
                try { rangeHeader = Request?.Headers["Range"]; } catch { }

                var isHead = false;
                try { isHead = string.Equals(Request?.Verb, "HEAD", StringComparison.OrdinalIgnoreCase); } catch { }

                var request = new HttpRequestMessage(isHead ? HttpMethod.Head : HttpMethod.Get, rawUrl);
                if (!string.IsNullOrEmpty(rangeHeader))
                    request.Headers.TryAddWithoutValidation("Range", rangeHeader);
                if (cfg != null && !string.IsNullOrWhiteSpace(cfg.UserAgent))
                    request.Headers.TryAddWithoutValidation("User-Agent", cfg.UserAgent);
                var refToUse = referer ?? cfg?.Referer;
                if (!string.IsNullOrWhiteSpace(refToUse))
                    request.Headers.TryAddWithoutValidation("Referer", refToUse);

                // playerContent 给的 header 优先（源站自带的 UA/Cookie/Referer 比我们猜的准）
                if (customHeaders != null)
                {
                    foreach (var kv in customHeaders)
                    {
                        // 头名/头值里出现 CR/LF/NUL 就是请求头注入：TryAddWithoutValidation
                        // 不会替你挡，实测能把 "a\r\nInjected: 1" 发成两个独立头。
                        var k = StripCtl(kv.Key);
                        var v = StripCtl(kv.Value);
                        if (k.Length == 0) continue;
                        // 逐跳/受控头不让源覆盖
                        if (k.Equals("Host", StringComparison.OrdinalIgnoreCase) ||
                            k.Equals("Range", StringComparison.OrdinalIgnoreCase) ||
                            k.Equals("Content-Length", StringComparison.OrdinalIgnoreCase) ||
                            k.StartsWith("Connection", StringComparison.OrdinalIgnoreCase) ||
                            k.StartsWith("Accept-Encoding", StringComparison.OrdinalIgnoreCase)) continue;
                        if (k.Equals("User-Agent", StringComparison.OrdinalIgnoreCase) ||
                            k.Equals("Referer", StringComparison.OrdinalIgnoreCase))
                        {
                            request.Headers.Remove(k);
                        }
                        request.Headers.TryAddWithoutValidation(k, v ?? "");
                    }
                }

                // 同步 Send：不需要续体线程，避免同步校验/播放时把 Emby 线程池抽干
                var resp = http.Send(request, HttpCompletionOption.ResponseHeadersRead);

                // 手动跟 302/301/307/308，每一跳都要重新校验目标 host（最多 3 跳）
                for (int hop = 0; hop < 3 && (int)resp.StatusCode >= 300 && (int)resp.StatusCode < 400; hop++)
                {
                    var loc = resp.Headers.Location?.ToString();
                    if (string.IsNullOrWhiteSpace(loc)) break;
                    string next;
                    try { next = new Uri(new Uri(rawUrl), loc).ToString(); } catch { break; }
                    if (IsBlockedHost(next))
                    {
                        resp.Dispose();
                        return PlainText(403, "blocked redirect host");
                    }
                    resp.Dispose();
                    var req2 = new HttpRequestMessage(isHead ? HttpMethod.Head : HttpMethod.Get, next);
                    if (!string.IsNullOrWhiteSpace(rangeHeader)) req2.Headers.TryAddWithoutValidation("Range", rangeHeader);
                    resp = http.Send(req2, HttpCompletionOption.ResponseHeadersRead);
                }

                var contentType = resp.Content.Headers.ContentType?.ToString() ?? "application/octet-stream";
                var isPlaylist = contentType.IndexOf("mpegurl", StringComparison.OrdinalIgnoreCase) >= 0
                                 || rawUrl.Contains(".m3u8", StringComparison.OrdinalIgnoreCase);

                if (isPlaylist)
                {
                    var text = resp.Content.ReadAsStringAsync().GetAwaiter().GetResult();
                    var rewritten = RewritePlaylist(text, rawUrl, referer ?? cfg?.Referer, req?.H);
                    var bytes = Encoding.UTF8.GetBytes(rewritten);
                    resp.Dispose();
                    http.Dispose();
                    return Factory().GetResult(Request, new MemoryStream(bytes),
                        "application/vnd.apple.mpegurl", null);
                }

                // HEAD 只要头，不要体（播放器常用 HEAD 探测是否可播）
                if (isHead)
                {
                    resp.Dispose();
                    http.Dispose();
                    SafeHeader("Accept-Ranges", "bytes");
                    return Factory().GetResult(Request, new MemoryStream(Array.Empty<byte>()), contentType, null);
                }

                var upstream = resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult();
                // Stream 的生命周期交给 Emby，Dispose 时连带释放 response / client
                var result = Factory().GetResult(Request,
                    new ResponseOwnedStream(upstream, resp, http), contentType, null);

                // 透传上游的 206 / Content-Range，否则客户端会以为不支持分段拖动
                SafeHeader("Accept-Ranges", "bytes");
                var cr = resp.Content.Headers.ContentRange;
                if (cr != null) SafeHeader("Content-Range", cr.ToString());
                if (result is MediaBrowser.Model.Services.IHttpResult hr && (int)resp.StatusCode == 206)
                {
                    hr.Status = 206;
                    hr.StatusCode = System.Net.HttpStatusCode.PartialContent;
                }
                return result;
            }
            catch (Exception ex)
            {
                return PlainText(502, "upstream error: " + ex.Message);
            }
        }

        /// <summary>把原始地址包装成带扩展名的代理路径，例如 /TvBox/Stream/seg0.ts?u=...</summary>
        public static string BuildProxyPath(string originalUrl, string referer = null, string headerB64 = null)
        {
            string name = "stream";
            try
            {
                var u = new Uri(originalUrl);
                name = Path.GetFileName(u.AbsolutePath);
                if (string.IsNullOrWhiteSpace(name)) name = "stream";

                // 分片没有扩展名、或者扩展名不是 hls 认识的媒体后缀时，统一改成 .ts。
                // 有的源分片长这样：.../novel-images/76b535...（没有扩展名），
                // ffmpeg 的 hls 解复用器会直接报 not in allowed_segment_extensions 拒掉整张列表。
                var ext = Path.GetExtension(name).ToLowerInvariant();
                bool known = ext == ".ts" || ext == ".m4s" || ext == ".mp4" || ext == ".aac"
                          || ext == ".mp3" || ext == ".m4a" || ext == ".vtt" || ext == ".key"
                          || ext == ".m3u8" || ext == ".mpd" || ext == ".webm" || ext == ".mkv"
                          || ext == ".flv" || ext == ".mov" || ext == ".avi";
                if (!known)
                {
                    if (ext.Length > 0) name = name.Substring(0, name.Length - ext.Length);
                    name = name + ".ts";
                }
                foreach (var c in Path.GetInvalidFileNameChars()) name = name.Replace(c, '_');
                if (name.Length > 64) name = name.Substring(name.Length - 64);
            }
            catch { }
            // 分片名可能含 ? # & 空格：不编码会把 ?u= 后面的查询串截断。
            var path = "/TvBox/Stream/" + Uri.EscapeDataString(name) + "?u=" + SyncService.Base64UrlEncode(originalUrl);
            if (!string.IsNullOrWhiteSpace(referer))
                path += "&r=" + SyncService.Base64UrlEncode(referer);
            if (!string.IsNullOrWhiteSpace(headerB64))
                path += "&h=" + headerB64;      // 分片也得带同一份 header，不然一样被拒
            return path;
        }

        /// <summary>
        /// 配置页引导脚本（外部 JS）。放在插件路由里而不是内联，是因为 Emby 用 innerHTML
        /// 插入插件页片段，内联 &lt;script&gt; 可能不执行 —— 外部脚本由内联事件属性去 append 才稳。
        /// 不含任何凭据，可以被页面直接引用。
        /// </summary>
        /// <summary>配置页引导用的空响应（iframe onload 触发点）。</summary>
        [Unauthenticated]
        [Route("/TvBox/Kick", "GET", Summary = "配置页引导 ping")]
        public class ConfigBootKick : IReturnVoid { }

        [Unauthenticated]
        [Route("/TvBox/ConfigBoot", "GET", Summary = "插件配置页的引导脚本")]
        public class ConfigBootScript : IReturnVoid { }

        /// <summary>
        /// 插件配置的只读快照（**不含共享令牌**），配置页用它先把值显示出来。
        /// 这样即便拿不到 Emby 的 ApiClient，页面也不会是空的。
        /// </summary>
        [Unauthenticated]
        [Route("/TvBox/ConfigPublic", "GET", Summary = "插件配置快照（不含令牌）")]
        public class ConfigPublicSnapshot : IReturn<object> { }

        /// <summary>写一个响应头。IHttpResult 没有 ResponseHeaders，只能走 IResponse。</summary>
        private void SafeHeader(string name, string value)
        {
            try { Response?.AddHeader(name, value); } catch { }
        }

        /// <summary>供配置页读取的配置快照：显式白名单，绝不带 HubToken。</summary>
        public object Get(ConfigPublicSnapshot request)
        {
            var cfg = P?.Configuration ?? new PluginConfiguration();
            var m = new Dictionary<string, object>
            {
                ["ConfigUrl"] = cfg.ConfigUrl,
                ["OutputRoot"] = cfg.OutputRoot,
                ["EmbyBaseUrl"] = cfg.EmbyBaseUrl,
                ["SpiderHubUrl"] = cfg.SpiderHubUrl,
                ["Referer"] = cfg.Referer,
                ["SiteWhitelist"] = cfg.SiteWhitelist,
                ["SiteBlacklist"] = cfg.SiteBlacklist,
                ["SiteNameFilter"] = cfg.SiteNameFilter,
                ["MaxPagesPerCategory"] = cfg.MaxPagesPerCategory,
                ["MaxItemsPerRun"] = cfg.MaxItemsPerRun,
                ["RequestDelayMs"] = cfg.RequestDelayMs,
                ["SyncIntervalHours"] = cfg.SyncIntervalHours,
                ["IncrementalHours"] = cfg.IncrementalHours,
                ["MaxDeletePerRun"] = cfg.MaxDeletePerRun,
                ["PosterMaxMb"] = cfg.PosterMaxMb,
                ["StreamMode"] = cfg.StreamMode,
                ["RedirectHosts"] = cfg.RedirectHosts,
                ["VerifyMaxEpisodes"] = cfg.VerifyMaxEpisodes,
                ["DetailBatchSize"] = cfg.DetailBatchSize,
                ["Concurrency"] = cfg.Concurrency,
                ["TimeoutSeconds"] = cfg.TimeoutSeconds,
                ["UserAgent"] = cfg.UserAgent,
                ["UseProxy"] = cfg.UseProxy,
                ["CleanupOrphans"] = cfg.CleanupOrphans,
                ["EnableScheduledSync"] = cfg.EnableScheduledSync,
                ["ExcludeCloudDrive"] = cfg.ExcludeCloudDrive,
                ["IncrementalSync"] = cfg.IncrementalSync,
                ["WriteNfo"] = cfg.WriteNfo,
                ["DownloadPoster"] = cfg.DownloadPoster,
                ["VerifyPlayUrl"] = cfg.VerifyPlayUrl,
                ["ResolveIndirectUrl"] = cfg.ResolveIndirectUrl,
                ["AllowPrivateHosts"] = cfg.AllowPrivateHosts,
                ["HubTokenSet"] = !string.IsNullOrWhiteSpace(cfg.HubToken),
                ["LastSyncUtc"] = cfg.LastSyncUtc,
                ["LastSyncSummary"] = cfg.LastSyncSummary
            };
            return m;
        }

        /// <summary>204 空响应：给 iframe onload 当触发点。</summary>
        public object Get(ConfigBootKick request)
        {
            try
            {
                var built = Factory().GetResult(Request, new MemoryStream(Array.Empty<byte>()),
                    "text/plain; charset=utf-8", null);
                if (built is MediaBrowser.Model.Services.IHttpResult hr)
                {
                    try { hr.Status = 204; } catch { }
                    try { hr.StatusCode = System.Net.HttpStatusCode.NoContent; } catch { }
                }
                return built;
            }
            catch (Exception) { return string.Empty; }
        }

        /// <summary>引导脚本：从程序集嵌入资源里取出 config-boot.js 原样返回。</summary>
        public object Get(ConfigBootScript request)
        {
            string js = null;
            try
            {
                var asm = typeof(Plugin).Assembly;
                foreach (var name in asm.GetManifestResourceNames())
                {
                    if (!name.EndsWith("config-boot.js", StringComparison.OrdinalIgnoreCase)) continue;
                    using (var st = asm.GetManifestResourceStream(name))
                    using (var rd = new StreamReader(st, Encoding.UTF8))
                        js = rd.ReadToEnd();
                    break;
                }
            }
            catch (Exception ex)
            {
                try { P?.Logger.ErrorException("读取 config-boot.js 失败", ex); } catch { }
            }
            if (js == null) js = "console.error('TvBox: config-boot.js 未嵌入插件');";
            try
            {
                var built = Factory().GetResult(Request,
                    new MemoryStream(Encoding.UTF8.GetBytes(js)), "application/javascript; charset=utf-8", null);
                if (built is MediaBrowser.Model.Services.IHttpResult hr) { try { hr.Status = 200; } catch { } }
                return built;
            }
            catch (Exception)
            {
                return js;
            }
        }

        private static IHttpResultFactory Factory()
        {
            var host = P?.ApplicationHost;
            if (host == null) throw new InvalidOperationException("插件未初始化");
            return host.Resolve<IHttpResultFactory>();
        }

        /// <summary>
        /// 构造一个 302 响应。Emby 的 API 走的是自己的 MediaBrowser.Model.Services，
        /// 没有 ServiceStack 的 HttpResult 可用，所以用 Factory 拿到底层结果对象，
        /// 再通过 IHttpResult（状态码）+ IHasHeaders（Location 头）两个接口拼出来。
        /// </summary>
        private object Redirect302(string url)
        {
            try
            {
                var built = Factory().GetResult(Request,
                    new MemoryStream(Array.Empty<byte>()), "text/plain; charset=utf-8", null);
                var ok = false;
                if (built is MediaBrowser.Model.Services.IHttpResult hr)
                {
                    try { hr.StatusCode = System.Net.HttpStatusCode.Redirect; ok = true; } catch { }
                }
                if (built is MediaBrowser.Model.Services.IHasHeaders hh)
                {
                    try { hh.Headers["Location"] = url; ok = true; } catch { }
                }
                return ok ? built : null;
            }
            catch (Exception ex)
            {
                try { P?.Logger.Debug("构造 302 失败，回落代理: " + ex.Message); } catch { }
                return null;
            }
        }

        /// <summary>规范化成绝对路径；失败返回 null（调用方按拒绝处理）。</summary>
        private static string SafeFullPath(string p)
        {
            if (string.IsNullOrWhiteSpace(p)) return null;
            try { return Path.GetFullPath(p.Trim()); } catch { return null; }
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
                    // Emby 4.9 里 IHttpResult.Status(int) 才是真正写回响应的那个，
                    // 只设 StatusCode 时实测一律返回 200。
                    try { hr.Status = status; } catch { }
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
        /// <summary>把 base64url 的 header JSON 解成字典；空/坏值返回 null。</summary>
        /// <summary>去掉会破坏 HTTP 头的控制字符（CR/LF/NUL/其它 C0）。</summary>
        private static string StripCtl(string s)
        {
            if (string.IsNullOrEmpty(s)) return s;
            var sb = new StringBuilder(s.Length);
            foreach (var c in s)
            {
                if (c == '\r' || c == '\n' || c == '\0' || (c < 0x20 && c != '\t')) continue;
                sb.Append(c);
            }
            return sb.ToString().Trim();
        }

        private static Dictionary<string, string> ParseHeaderB64(string b64)
        {
            if (string.IsNullOrWhiteSpace(b64)) return null;
            try
            {
                var json = SyncService.Base64UrlDecode(b64);
                if (string.IsNullOrWhiteSpace(json)) return null;
                var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                using var doc = System.Text.Json.JsonDocument.Parse(json);
                foreach (var prop in doc.RootElement.EnumerateObject())
                    dict[prop.Name] = prop.Value.ToString();
                return dict.Count > 0 ? dict : null;
            }
            catch { return null; }
        }

        private static string RewritePlaylist(string text, string playlistUrl, string referer = null, string headerB64 = null)
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
                        // 密钥/key 也要带 Referer+header：AES-128 源对 key 请求同样查防盗链，
                        // 不带就整条播放 403（以前这里只传了 URL）。
                        sb.Append(RewriteUriAttribute(line, baseUri, referer, headerB64)).Append('\n');
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

                sb.Append(BuildProxyPath(abs, referer, headerB64)).Append('\n');
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

        private static string RewriteUriAttribute(string line, Uri baseUri,
                                                string referer = null, string headerB64 = null)
        {
            var idx = line.IndexOf("URI=\"", StringComparison.Ordinal);
            if (idx < 0) return line;
            var start = idx + 5;
            var end = line.IndexOf('"', start);
            if (end < 0) return line;
            var uri = line.Substring(start, end - start);
            var abs = ToAbsolute(uri, baseUri);
            if (abs == null) return line;
            return line.Substring(0, start) + BuildProxyPath(abs, referer, headerB64) + line.Substring(end);
        }

        private static string ToAbsolute(string url, Uri baseUri)
        {
            try
            {
                if (url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                    url.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                    return url;
                if (baseUri == null) return null;
                var abs = new Uri(baseUri, url).ToString();

                // 分片是相对路径时，播放列表上的鉴权查询串要带着走。
                // 实测 ppvod 这类 CDN：m3u8 是 .../index.m3u8?auth_key=xxx，
                // 分片写成 index0.ts（不带 query）。按 RFC 解析出来的分片 URL 没有
                // auth_key，代理去拉就 401 —— 表现为「首个分片返回 401」整条判死。
                if (!abs.Contains("?") && !string.IsNullOrEmpty(baseUri.Query) && baseUri.Query.Length > 1)
                    abs = abs + baseUri.Query;
                return abs;
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
        /// <summary>
        /// 判断一个 IP 是否属于「不该被代理访问」的地址段。
        /// allowPrivate=false 时连 10/172.16/192.168 一起拒（默认）。
        /// </summary>
        private static bool IsBlockedIp(System.Net.IPAddress ip, bool allowPrivate)
        {
            if (ip == null) return true;
            if (System.Net.IPAddress.IsLoopback(ip)) return true;              // 127/8、::1
            if (ip.Equals(System.Net.IPAddress.Any) || ip.Equals(System.Net.IPAddress.IPv6Any)) return true;
            if (ip.IsIPv6LinkLocal) return true;                                // fe80::/10
            if (ip.IsIPv6SiteLocal) return true;

            // IPv4-mapped IPv6（::ffff:127.0.0.1 之类）先摊平成 IPv4 再判
            if (ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6 && ip.IsIPv4MappedToIPv6)
                ip = ip.MapToIPv4();

            var b = ip.GetAddressBytes();
            if (b.Length == 4)
            {
                if (b[0] == 0) return true;
                if (b[0] == 169 && b[1] == 254) return true;                    // link-local + 云元数据
                if (b[0] >= 224) return true;                                   // 组播/保留
                if (!allowPrivate)
                {
                    if (b[0] == 10) return true;
                    if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) return true;
                    if (b[0] == 192 && b[1] == 168) return true;
                    if (b[0] == 100 && b[1] >= 64 && b[1] <= 127) return true;  // CGNAT
                }
                return false;
            }
            if (b.Length == 16)
            {
                if ((b[0] & 0xFE) == 0xFC) return !allowPrivate;                 // fc00::/7 ULA
                return false;
            }
            return true;
        }

        private bool IsBlockedHost(string url)
        {
            bool allowPrivate = P?.Configuration?.AllowPrivateHosts == true;
            try
            {
                var u = new Uri(url);
                var host = u.Host;
                if (host.Equals("localhost", StringComparison.OrdinalIgnoreCase)) return true;
                if (host.Equals("localhost.localdomain", StringComparison.OrdinalIgnoreCase)) return true;
                if (host.EndsWith(".localhost", StringComparison.OrdinalIgnoreCase)) return true;
                if (host.EndsWith(".local", StringComparison.OrdinalIgnoreCase)) return true;

                if (System.Net.IPAddress.TryParse(host, out var ip))
                    return IsBlockedIp(ip, allowPrivate);

                // 关键：非 IP 主机名必须真的去解析。
                // 以前这里直接 return false —— 于是 127.0.0.1.nip.io 这类指向回环的域名
                // 轻松绕过「禁止访问本机」，把只监听 127.0.0.1 的 hub 管理面暴露给任何
                // 能访问 Emby 8096 的人。解析失败一律按非法处理。
                System.Net.IPAddress[] addrs;
                try
                {
                    addrs = System.Net.Dns.GetHostAddresses(host);
                }
                catch { return true; }
                if (addrs == null || addrs.Length == 0) return true;
                foreach (var a in addrs)
                    if (IsBlockedIp(a, allowPrivate)) return true;
                return false;
            }
            catch { return true; }
        }

        private bool IsBlockedHostLegacy(string url)
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
