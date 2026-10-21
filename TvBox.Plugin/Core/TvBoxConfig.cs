using System;
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
                foreach (var entry in root.Urls)
                {
                    ct.ThrowIfCancellationRequested();
                    if (entry?.Url == null) continue;
                    try
                    {
                        var sub = await LoadOneAsync(entry.Url, ct, 1).ConfigureAwait(false);
                        if (sub.Sites != null) result.Sites.AddRange(sub.Sites);
                        if (sub.Lives != null) result.Lives.AddRange(sub.Lives);
                        if (sub.Parses != null) result.Parses.AddRange(sub.Parses);
                        _log($"  子仓 {entry.Name ?? entry.Url} → {sub.Sites?.Count ?? 0} 个站点");
                    }
                    catch (Exception ex)
                    {
                        var msg = $"子仓 {entry.Name ?? entry.Url} 拉取失败: {ex.Message}";
                        result.LoadWarnings.Add(msg);
                        _log("  " + msg);
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
                using (var resp = await _http.GetAsync(ref_, HttpCompletionOption.ResponseContentRead, ct).ConfigureAwait(false))
                {
                    resp.EnsureSuccessStatusCode();
                    text = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
                }
            }
            else if (File.Exists(ref_))
            {
                text = await File.ReadAllTextAsync(ref_, ct).ConfigureAwait(false);
            }
            else
            {
                throw new FileNotFoundException($"配置既不是 http(s) 地址，本地也找不到文件: {ref_}");
            }

            return ParseText(text, ref_);
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
