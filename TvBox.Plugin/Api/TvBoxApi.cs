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

    /// <summary>同步范围配置：hub 的 Web 界面直接读写，不用进 Emby 后台。</summary>
    [Unauthenticated]
    [Route("/TvBox/Config", "POST", Summary = "更新同步范围（白名单/页数/并发等）")]
    public class UpdateConfig : IReturn<SimpleResult>
    {
        public string Token { get; set; }
        public string SiteWhitelist { get; set; }
        public string SiteBlacklist { get; set; }
        public int? MaxPagesPerCategory { get; set; }
        public int? MaxItemsPerRun { get; set; }
        public int? Concurrency { get; set; }
        public int? TimeoutSeconds { get; set; }
        public string OutputRoot { get; set; }
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
                return Factory().GetResult(Request,
                    new MemoryStream(Encoding.UTF8.GetBytes(text)), "text/plain; charset=utf-8", null);
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
                sb.Append(BuildProxyPath(abs)).Append('\n');
            }
            return sb.ToString();
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

        /// <summary>SSRF 防护：默认不允许代理到本机/内网地址（AllowPrivateHosts 可放行）。</summary>
        private bool IsBlockedHost(string url)
        {
            if (P?.Configuration?.AllowPrivateHosts == true) return false;
            try
            {
                var u = new Uri(url);
                var host = u.Host;
                if (host.Equals("localhost", StringComparison.OrdinalIgnoreCase)) return true;
                if (System.Net.IPAddress.TryParse(host, out var ip))
                {
                    var b = ip.GetAddressBytes();
                    if (b.Length == 4)
                    {
                        if (b[0] == 10) return true;
                        if (b[0] == 127) return true;
                        if (b[0] == 169 && b[1] == 254) return true;
                        if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) return true;
                        if (b[0] == 192 && b[1] == 168) return true;
                    }
                    return ip.Equals(System.Net.IPAddress.IPv6Loopback);
                }
                return false;
            }
            catch { return true; }
        }

    }
}
