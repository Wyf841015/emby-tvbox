using System;
using System.Linq;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;

namespace TvBox.Plugin.Core
{
    /// <summary>TVBox 配置里的一个点播站点。</summary>
    public class TvBoxSite
    {
        [JsonPropertyName("key")] public string Key { get; set; }
        [JsonPropertyName("name")] public string Name { get; set; }
        [JsonPropertyName("type")] public int Type { get; set; }
        [JsonPropertyName("api")] public string Api { get; set; }
        [JsonPropertyName("searchable")] public int Searchable { get; set; }
        [JsonPropertyName("quickSearch")] public int QuickSearch { get; set; }
        [JsonPropertyName("filterable")] public int Filterable { get; set; }
        [JsonPropertyName("jar")] public string Jar { get; set; }
        [JsonPropertyName("ext")] public JsonElement? Ext { get; set; }

        /// <summary>
        /// 校验/播放时该带的 Referer。很多 CDN 只认自己站点的 Referer，不带就 403，
        /// 会被当成死链整条丢掉。优先取 api 的源站；spider 站的 api 是 csp_Xxx，退回 ext 里的域名。
        /// </summary>
        public string Referer
        {
            get
            {
                var r = OriginOf(Api);
                if (r != null) return r;
                if (Ext.HasValue && Ext.Value.ValueKind == JsonValueKind.String)
                    return OriginOf(Ext.Value.GetString());
                return null;
            }
        }

        private static string OriginOf(string url)
        {
            if (string.IsNullOrWhiteSpace(url)) return null;
            if (!url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) &&
                !url.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) return null;
            try { return new Uri(url).GetLeftPart(UriPartial.Authority) + "/"; }
            catch { return null; }
        }

        /// <summary>
        /// 本插件能否处理这个站。
        /// type 0 = CMS XML，type 1 = CMS JSON —— 直接支持。
        /// type 3/4 是 spider（jar 或 drpy js），C# 跑不了，明确不支持。
        /// 另外 api 必须是 http(s)，drpy 那种 "./js/drpy.min.js" 相对路径也排除。
        /// </summary>
        [JsonIgnore]
        public bool Supported
        {
            get
            {
                if (Type != 0 && Type != 1) return false;
                if (string.IsNullOrWhiteSpace(Api)) return false;
                if (!Api.StartsWith("http://", StringComparison.OrdinalIgnoreCase) &&
                    !Api.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) return false;
                return true;
            }
        }

        [JsonIgnore]
        public string DisplayName => string.IsNullOrWhiteSpace(Name) ? (Key ?? "?") : Name;
    }

    /// <summary>多仓配置里的一个仓库条目。</summary>
    public class TvBoxRepoEntry
    {
        [JsonPropertyName("url")] public string Url { get; set; }
        [JsonPropertyName("name")] public string Name { get; set; }
    }

    /// <summary>解析后的 TVBox 配置。</summary>
    public class TvBoxConfigDocument
    {
        [JsonPropertyName("sites")] public List<TvBoxSite> Sites { get; set; }
        [JsonPropertyName("lives")] public List<JsonElement> Lives { get; set; }
        [JsonPropertyName("parses")] public List<JsonElement> Parses { get; set; }
        [JsonPropertyName("urls")] public List<TvBoxRepoEntry> Urls { get; set; }

        /// <summary>多仓展开时记录来源，便于在配置页展示。</summary>
        [JsonIgnore] public List<string> LoadWarnings { get; } = new List<string>();
    }

    /// <summary>
    /// 负责把各种形态的 TVBox 配置拉下来并还原成站点列表：
    ///   - 普通 JSON
    ///   - 整段 base64 的 JSON
    ///   - 多仓 {"urls":[...]} → 逐个拉取合并
    ///   - 本地文件路径
    /// </summary>
    public class TvBoxConfigLoader
    {
        private readonly HttpClient _http;
        private readonly Action<string> _log;

        public TvBoxConfigLoader(HttpClient http, Action<string> log = null)
        {
            _http = http;
            _log = log ?? (_ => { });
        }

        private static readonly JsonSerializerOptions JsonOpts = new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true,
            ReadCommentHandling = JsonCommentHandling.Skip,
            AllowTrailingCommas = true,
            NumberHandling = JsonNumberHandling.AllowReadingFromString,
        };

        /// <summary>
        /// 拉取并解析配置。失败抛异常，成功返回合并后的文档。
        /// </summary>
        public async Task<TvBoxConfigDocument> LoadAsync(string source, CancellationToken ct)
        {
            if (string.IsNullOrWhiteSpace(source))
                throw new ArgumentException("未配置 TVBox 配置地址");

            var root = await LoadOneAsync(source.Trim(), ct, 0).ConfigureAwait(false);
            var result = new TvBoxConfigDocument
            {
                Sites = new List<TvBoxSite>(),
                Lives = new List<JsonElement>(),
                Parses = new List<JsonElement>(),
            };

            if (root.Urls != null && root.Urls.Count > 0)
            {
                // 多仓：逐个子仓拉取
                _log($"这是多仓配置，共 {root.Urls.Count} 个子仓");
                // 串行拉 18 个子仓要 50 秒，配置页那个请求等不到就超时了。
                // 这里并发 6 个，整体压到十秒内。
                var entries = root.Urls.Where(e => e?.Url != null).ToList();
                using (var subCts = CancellationTokenSource.CreateLinkedTokenSource(ct))
                using (var gate = new SemaphoreSlim(10))
                {
                  // 整批子仓最多等 15 秒：配置页那个请求等不起 20 秒以上。
                  // 到点还没拉完的按警告丢弃，已拿到的照常返回。
                  subCts.CancelAfter(TimeSpan.FromSeconds(15));

                    var tasks = entries.Select(async entry =>
                    {
                        await gate.WaitAsync(subCts.Token).ConfigureAwait(false);
                        try
                        {
                            subCts.Token.ThrowIfCancellationRequested();
                            var sub = await LoadOneAsync(entry.Url, subCts.Token, 1).ConfigureAwait(false);
                            _log($"  子仓 {entry.Name ?? entry.Url} → {sub.Sites?.Count ?? 0} 个站点");
                            return (entry, sub, (Exception)null);
                        }
                        catch (OperationCanceledException) when (ct.IsCancellationRequested)
                        {
                            throw;
                        }
                        catch (OperationCanceledException)
                        {
                            return (entry, (TvBoxConfigDocument)null,
                                    (Exception)new TimeoutException("超过 15 秒总预算，已跳过"));
                        }
                        catch (Exception ex)
                        {
                            return (entry, (TvBoxConfigDocument)null, ex);
                        }
                        finally
                        {
                            gate.Release();
                        }
                    }).ToList();

                    var done = await Task.WhenAll(tasks).ConfigureAwait(false);
                    // 按原始顺序合并，保证结果稳定（并发完成顺序不固定）
                    foreach (var (entry, sub, ex) in done)
                    {
                        if (ex != null)
                        {
                            var msg = $"子仓 {entry.Name ?? entry.Url} 拉取失败: {Short(ex)}";
                            result.LoadWarnings.Add(msg);
                            _log("  " + msg);
                            continue;
                        }
                        if (sub?.Sites != null) result.Sites.AddRange(sub.Sites);
                        if (sub?.Lives != null) result.Lives.AddRange(sub.Lives);
                        if (sub?.Parses != null) result.Parses.AddRange(sub.Parses);
                    }
                }
            }
            else
            {
                if (root.Sites != null) result.Sites.AddRange(root.Sites);
                if (root.Lives != null) result.Lives.AddRange(root.Lives);
                if (root.Parses != null) result.Parses.AddRange(root.Parses);
            }

            // 去掉重复 key（多仓之间经常有重复）
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var deduped = new List<TvBoxSite>();
            foreach (var s in result.Sites)
            {
                if (s == null) continue;
                var k = s.Key ?? (s.Name + "|" + s.Api);
                if (!seen.Add(k)) continue;
                deduped.Add(s);
            }
            result.Sites = deduped;
            return result;
        }

        private async Task<TvBoxConfigDocument> LoadOneAsync(string ref_, CancellationToken ct, int depth)
        {
            string text;
            if (ref_.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                ref_.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            {
                text = await FetchWithMirrorsAsync(ref_, ct).ConfigureAwait(false);
            }
            else if (File.Exists(ref_))
            {
                text = await File.ReadAllTextAsync(ref_, ct).ConfigureAwait(false);
            }
            else
            {
                throw new FileNotFoundException($"配置既不是 http(s) 地址，本地也找不到文件: {ref_}");
            }

            // 有些源返回带 BOM 的 UTF-8，直接用 System.Text.Json 会报
            // "'0xEF' is an invalid start of a value"。BOM 和前后空白都要清掉。
            text = text.TrimStart('\uFEFF', '\u200B', ' ', '\r', '\n', '\t').TrimEnd();

            return ParseText(text, ref_);
        }

        /// <summary>
        /// 拉配置内容。raw.githubusercontent.com 在国内经常超时或只有 AAAA 记录，
        /// 直连失败就依次换镜像（和 spider-hub 用的是同一批）。
        /// </summary>
        private async Task<string> FetchWithMirrorsAsync(string url, CancellationToken ct)
        {
            var candidates = new List<string> { url };

            var m = System.Text.RegularExpressions.Regex.Match(url,
                @"^https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$",
                System.Text.RegularExpressions.RegexOptions.IgnoreCase);
            if (m.Success)
            {
                var user = m.Groups[1].Value;
                var repo = m.Groups[2].Value;
                var branch = m.Groups[3].Value;
                var path = m.Groups[4].Value;
                candidates.Add("https://ghproxy.net/" + url);
                candidates.Add("https://gh-proxy.com/" + url);
                candidates.Add($"https://cdn.jsdelivr.net/gh/{user}/{repo}@{branch}/{path}");
                candidates.Add($"https://fastly.jsdelivr.net/gh/{user}/{repo}@{branch}/{path}");
            }
            else if (url.Contains("github.com"))
            {
                candidates.Add("https://ghproxy.net/" + url);
                candidates.Add("https://gh-proxy.com/" + url);
            }

            var errors = new List<string>();
            foreach (var cand in candidates)
            {
                ct.ThrowIfCancellationRequested();
                try
                {
                    // 单个候选地址最多等 8 秒：18 个子仓里总有死站，
                    // 用 HttpClient 的 30s 全局超时会被最慢那个拖死（实测 49s）。
                    using (var cts = CancellationTokenSource.CreateLinkedTokenSource(ct))
                    {
                    cts.CancelAfter(TimeSpan.FromSeconds(5));
                    using (var req = new HttpRequestMessage(HttpMethod.Get, cand))
                    using (var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseContentRead, cts.Token).ConfigureAwait(false))
                    {
                        if (!resp.IsSuccessStatusCode)
                        {
                            errors.Add($"{Host(cand)} → HTTP {(int)resp.StatusCode}");
                            // 4xx（尤其 404）换镜像也救不回来，别浪费后面的超时预算
                            if ((int)resp.StatusCode >= 400 && (int)resp.StatusCode < 500) break;
                            continue;
                        }
                        var body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
                        if (string.IsNullOrWhiteSpace(body))
                        {
                            errors.Add($"{Host(cand)} → 空响应");
                            continue;
                        }
                        if (cand != url) _log($"  镜像可用: {Host(cand)}");
                        return body;
                    }
                    }
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception ex)
                {
                    errors.Add($"{Host(cand)} → {Short(ex)}");
                    // 域名都不存在，换镜像没意义
                    if (Short(ex).IndexOf("Name or service not known", StringComparison.OrdinalIgnoreCase) >= 0
                        || Short(ex).IndexOf("No such host", StringComparison.OrdinalIgnoreCase) >= 0) break;
                }
            }
            throw new InvalidOperationException(
                $"全部 {candidates.Count} 个地址都拉不到（{string.Join("；", errors)}）");
        }

        private static string Host(string url)
        {
            try { return new Uri(url).Host; } catch { return url; }
        }

        private static string Short(Exception ex)
        {
            var e = ex;
            while (e.InnerException != null && e.InnerException != e) e = e.InnerException;
            var msg = e.Message ?? e.GetType().Name;
            return msg.Length > 80 ? msg.Substring(0, 80) + "…" : msg;
        }

        /// <summary>
        /// 把 type:3 + csp_XXX 的 spider 站点重写成 spider-hub 的 MacCMS 采集地址。
        /// 改写后它们就是标准 type:1 站点，后续同步逻辑完全复用，无需特殊分支。
        /// </summary>
        public static int ApplySpiderHub(TvBoxConfigDocument doc, string hubUrl)
        {
            if (doc?.Sites == null || string.IsNullOrWhiteSpace(hubUrl)) return 0;
            var hub = hubUrl.Trim().TrimEnd('/');
            int n = 0;
            foreach (var site in doc.Sites)
            {
                if (site == null) continue;
                var api = site.Api ?? "";
                if (site.Type != 3 || !api.StartsWith("csp_", StringComparison.OrdinalIgnoreCase)) continue;
                if (string.IsNullOrWhiteSpace(site.Key)) continue;

                site.Type = 1;   // 当成 CMS JSON 采集站
                site.Api = hub + "/v/" + Uri.EscapeDataString(site.Key) + "/api.php/provide/vod/";
                site.Jar = null;
                n++;
            }
            return n;
        }

        /// <summary>解析配置文本，自动识别 base64。</summary>
        public static TvBoxConfigDocument ParseText(string text, string origin = null)
        {
            if (string.IsNullOrWhiteSpace(text))
                throw new InvalidDataException("配置内容为空");

            var trimmed = text.Trim();

            // 有些源会返回 BOM 或者前后包裹的空白/换行，先剥离
            trimmed = trimmed.TrimStart('\uFEFF', '\u200B');

            // base64 形态：合法 JSON 一定以 { 或 [ 开头
            if (trimmed.Length > 8 && trimmed[0] != '{' && trimmed[0] != '[')
            {
                try
                {
                    var bytes = Convert.FromBase64String(trimmed);
                    var decoded = Encoding.UTF8.GetString(bytes).Trim();
                    if (decoded.StartsWith("{") || decoded.StartsWith("["))
                        trimmed = decoded;
                }
                catch (FormatException)
                {
                    // 不是 base64，保持原样往下走，让 JSON 解析器报出真实错误
                }
            }

            // 真实源的子仓大量返回网页而不是 JSON（域名过期变导航页、被运营商/安全软件拦截等）。
            // 这时候报 JSON 语法错误对用户毫无意义，直接说清楚。
            if (trimmed.StartsWith("<", StringComparison.Ordinal))
            {
                string hint = "返回的是网页（HTML），不是 TVBox 配置";
                if (trimmed.IndexOf("360-analysis-id", StringComparison.OrdinalIgnoreCase) >= 0 ||
                    trimmed.IndexOf("拦截", StringComparison.OrdinalIgnoreCase) >= 0)
                    hint += "，看起来被安全软件/运营商拦截了";
                else if (trimmed.IndexOf("<title>", StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    var a = trimmed.IndexOf("<title>", StringComparison.OrdinalIgnoreCase) + 7;
                    var b = trimmed.IndexOf("</title>", a, StringComparison.OrdinalIgnoreCase);
                    if (b > a && b - a < 60)
                        hint += "，页面标题：" + trimmed.Substring(a, b - a).Trim();
                    hint += "。多半是配置地址已失效，跳到了导航页/首页";
                }
                throw new InvalidDataException($"{hint}（{origin ?? "内联"}）");
            }

            TvBoxConfigDocument doc;
            try
            {
                doc = JsonSerializer.Deserialize<TvBoxConfigDocument>(trimmed, JsonOpts);
            }
            catch (JsonException ex)
            {
                throw new InvalidDataException(
                    $"配置不是合法 JSON（来源 {origin ?? "内联"}）: {ex.Message}", ex);
            }

            if (doc == null)
                throw new InvalidDataException("配置解析结果为空");

            doc.Sites ??= new List<TvBoxSite>();
            return doc;
        }
    }
}
