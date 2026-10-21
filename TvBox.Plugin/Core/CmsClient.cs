using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Xml.Linq;

namespace TvBox.Plugin.Core
{
    public class VodCategory
    {
        public string Id { get; set; }
        public string Name { get; set; }
    }

    public class VodEpisode
    {
        public string Name { get; set; }
        public string Url { get; set; }
    }

    public class VodLine
    {
        /// <summary>线路名（vod_play_from 里的名字，比如「量子」「非凡」）。</summary>
        public string Name { get; set; }
        public List<VodEpisode> Episodes { get; set; } = new List<VodEpisode>();
    }

    public class VodItem
    {
        public string Id { get; set; }
        public string Name { get; set; }
        public string Pic { get; set; }
        public string Remarks { get; set; }
        public string Year { get; set; }
        public string Area { get; set; }
        public string TypeName { get; set; }
        public string Content { get; set; }
        public List<VodLine> Lines { get; set; } = new List<VodLine>();

        /// <summary>总集数（所有线路里最多的那条）。</summary>
        public int EpisodeCount => Lines.Count == 0 ? 0 : Lines.Max(l => l.Episodes.Count);

        /// <summary>是剧集还是电影：多集就当剧集处理。</summary>
        public bool IsSeries => EpisodeCount > 1;
    }

    public class PagedResult
    {
        public List<VodItem> Items { get; set; } = new List<VodItem>();
        public int Page { get; set; }
        public int PageCount { get; set; }
    }

    /// <summary>
    /// 苹果 CMS（MacCMS v10）接口客户端。TVBox 里 type=0（XML）和 type=1（JSON）
    /// 本质上都是这套接口，只是返回格式不同，这里统一处理。
    ///
    /// 用到的三个动作：
    ///   ac=list        分类 + 列表
    ///   ac=videolist   列表（带 t 分类 / pg 页码 / wd 关键词）
    ///   ac=detail      详情（ids），才带 vod_play_url
    /// </summary>
    public class CmsClient
    {
        private readonly HttpClient _http;
        private readonly Action<string> _log;
        private readonly string _referer;

        public CmsClient(HttpClient http, Action<string> log = null, string referer = null)
        {
            _http = http;
            _log = log ?? (_ => { });
            _referer = referer;
        }

        private static string BuildUrl(string api, string query)
        {
            if (string.IsNullOrEmpty(api)) return api;
            var sep = api.Contains("?") ? "&" : "?";
            // api 以 ? 或 & 结尾时不要再补分隔符
            if (api.EndsWith("?") || api.EndsWith("&")) sep = "";
            return api + sep + query;
        }

        private async Task<string> GetStringAsync(string url, CancellationToken ct)
        {
            using (var req = new HttpRequestMessage(HttpMethod.Get, url))
            {
                if (!string.IsNullOrWhiteSpace(_referer))
                    req.Headers.TryAddWithoutValidation("Referer", _referer);
                using (var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseContentRead, ct).ConfigureAwait(false))
                {
                    resp.EnsureSuccessStatusCode();
                    return await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
                }
            }
        }

        // ───────────────────────── 分类 ─────────────────────────

        public async Task<List<VodCategory>> GetCategoriesAsync(TvBoxSite site, CancellationToken ct)
        {
            var url = BuildUrl(site.Api, "ac=list");
            var body = await GetStringAsync(url, ct).ConfigureAwait(false);
            return ParseCategories(body);
        }

        public static List<VodCategory> ParseCategories(string body)
        {
            var list = new List<VodCategory>();
            if (string.IsNullOrWhiteSpace(body)) return list;
            var t = body.TrimStart();

            if (t.StartsWith("{"))
            {
                using (var doc = JsonDocument.Parse(t))
                {
                    if (TryGetProp(doc.RootElement, "class", out var classes) &&
                        classes.ValueKind == JsonValueKind.Array)
                    {
                        foreach (var c in classes.EnumerateArray())
                        {
                            var id = GetStringFlexible(c, "type_id", "type_pid", "id");
                            var name = GetStringFlexible(c, "type_name", "name");
                            if (!string.IsNullOrEmpty(id))
                                list.Add(new VodCategory { Id = id, Name = name ?? id });
                        }
                    }
                }
            }
            else if (t.StartsWith("<"))
            {
                var xml = XDocument.Parse(t);
                // <class><ty id="1">电影</ty>...</class>
                foreach (var ty in xml.Descendants("ty"))
                {
                    var id = (string)ty.Attribute("id");
                    if (!string.IsNullOrEmpty(id))
                        list.Add(new VodCategory { Id = id, Name = ty.Value?.Trim() ?? id });
                }
            }
            return list;
        }

        // ───────────────────────── 列表 ─────────────────────────

        public async Task<PagedResult> GetListAsync(TvBoxSite site, string typeId, int page, CancellationToken ct)
        {
            var q = $"ac=videolist&pg={page}";
            if (!string.IsNullOrEmpty(typeId)) q += "&t=" + Uri.EscapeDataString(typeId);
            var url = BuildUrl(site.Api, q);
            string body;
            try
            {
                body = await GetStringAsync(url, ct).ConfigureAwait(false);
            }
            catch (HttpRequestException)
            {
                // 有些站不认 ac=videolist，退回 ac=detail
                body = await GetStringAsync(BuildUrl(site.Api,
                    $"ac=detail&pg={page}" + (string.IsNullOrEmpty(typeId) ? "" : "&t=" + Uri.EscapeDataString(typeId))),
                    ct).ConfigureAwait(false);
            }
            return ParseList(body, page);
        }

        public static PagedResult ParseList(string body, int requestedPage)
        {
            var result = new PagedResult { Page = requestedPage };
            if (string.IsNullOrWhiteSpace(body)) return result;

            var t = body.TrimStart();
            if (t.StartsWith("{"))
            {
                using (var doc = JsonDocument.Parse(t))
                {
                    var root = doc.RootElement;
                    result.Page = GetIntFlexible(root, "page") ?? requestedPage;
                    result.PageCount = GetIntFlexible(root, "pagecount") ?? 1;
                    if (TryGetProp(root, "list", out var arr) && arr.ValueKind == JsonValueKind.Array)
                        foreach (var el in arr.EnumerateArray())
                            result.Items.Add(ParseVodFromJson(el));
                }
            }
            else if (t.StartsWith("<"))
            {
                var xml = XDocument.Parse(t);
                var listEl = xml.Descendants("list").FirstOrDefault();
                if (listEl != null)
                {
                    result.Page = ParseIntAttr(listEl, "page", requestedPage);
                    result.PageCount = ParseIntAttr(listEl, "pagecount", 1);
                }
                foreach (var v in xml.Descendants("video"))
                    result.Items.Add(ParseVodFromXml(v));
            }
            return result;
        }

        // ───────────────────────── 详情 ─────────────────────────

        public async Task<List<VodItem>> GetDetailAsync(TvBoxSite site, IEnumerable<string> ids, CancellationToken ct)
        {
            var idList = string.Join(",", ids.Where(x => !string.IsNullOrEmpty(x)));
            if (string.IsNullOrEmpty(idList)) return new List<VodItem>();
            var url = BuildUrl(site.Api, "ac=detail&ids=" + Uri.EscapeDataString(idList));
            var body = await GetStringAsync(url, ct).ConfigureAwait(false);
            var items = ParseDetail(body);
            // 播放地址常常是相对路径，这里按站点 scheme+host 绝对化（否则 strm 播不了）
            foreach (var it in items) ResolveRelativePlayUrls(it, site.Api);
            return items;
        }

        public static List<VodItem> ParseDetail(string body)
        {
            var items = new List<VodItem>();
            if (string.IsNullOrWhiteSpace(body)) return items;
            var t = body.TrimStart();

            if (t.StartsWith("{"))
            {
                using (var doc = JsonDocument.Parse(t))
                {
                    if (TryGetProp(doc.RootElement, "list", out var arr) && arr.ValueKind == JsonValueKind.Array)
                        foreach (var el in arr.EnumerateArray())
                            items.Add(ParseVodFromJson(el));
                }
            }
            else if (t.StartsWith("<"))
            {
                var xml = XDocument.Parse(t);
                foreach (var v in xml.Descendants("video"))
                    items.Add(ParseVodFromXml(v));
            }
            return items;
        }

        // ───────────────────────── 解析：JSON ─────────────────────────

        private static VodItem ParseVodFromJson(JsonElement el)
        {
            var item = new VodItem
            {
                Id = GetStringFlexible(el, "vod_id", "id"),
                Name = GetStringFlexible(el, "vod_name", "name"),
                Pic = GetStringFlexible(el, "vod_pic", "pic", "vod_pic_thumb"),
                Remarks = GetStringFlexible(el, "vod_remarks", "remarks", "note"),
                Year = GetStringFlexible(el, "vod_year", "year"),
                Area = GetStringFlexible(el, "vod_area", "area"),
                TypeName = GetStringFlexible(el, "vod_class", "type_name", "class"),
                Content = GetStringFlexible(el, "vod_content", "content", "des"),
            };
            var playFrom = GetStringFlexible(el, "vod_play_from", "play_from");
            var playUrl = GetStringFlexible(el, "vod_play_url", "play_url");
            item.Lines = PlayUrlParser.Parse(playFrom, playUrl);
            return item;
        }

        /// <summary>
        /// 很多 CMS/spider 的 vod_play_url 是相对路径（如 /play/50368-1-21.html），
        /// 原样写进 strm 播放器根本打不开。这里按站点采集地址的 scheme+host 补成绝对地址。
        /// </summary>
        private static void ResolveRelativePlayUrls(VodItem item, string apiUrl)
        {
            if (item?.Lines == null || string.IsNullOrWhiteSpace(apiUrl)) return;
            string origin = null;
            try
            {
                var u = new Uri(apiUrl);
                origin = u.GetLeftPart(UriPartial.Authority);   // https://host
            }
            catch { return; }
            if (string.IsNullOrEmpty(origin)) return;

            foreach (var line in item.Lines)
            {
                if (line?.Episodes == null) continue;
                foreach (var ep in line.Episodes)
                {
                    var u = ep?.Url;
                    if (string.IsNullOrWhiteSpace(u)) continue;
                    if (u.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                        u.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) continue;
                    if (u.StartsWith("//")) { ep.Url = "https:" + u; continue; }
                    if (u.StartsWith("/")) { ep.Url = origin + u; continue; }
                    // 形如 play/123.html —— 按站点根目录拼
                    if (!u.Contains("://")) ep.Url = origin + "/" + u.TrimStart('.', '/');
                }
            }
        }

        // ───────────────────────── 解析：XML ─────────────────────────

        private static VodItem ParseVodFromXml(XElement v)
        {
            var item = new VodItem
            {
                Id = ChildVal(v, "vod_id"),
                Name = ChildVal(v, "vod_name"),
                Pic = ChildVal(v, "vod_pic"),
                Remarks = ChildVal(v, "vod_remarks"),
                Year = ChildVal(v, "vod_year"),
                Area = ChildVal(v, "vod_area"),
                TypeName = ChildVal(v, "vod_class"),
                Content = ChildVal(v, "vod_content"),
            };
            item.Lines = PlayUrlParser.Parse(ChildVal(v, "vod_play_from"), ChildVal(v, "vod_play_url"));
            return item;
        }

        private static string ChildVal(XElement e, string name)
        {
            var c = e.Element(name);
            return c?.Value?.Trim();
        }

        private static int ParseIntAttr(XElement e, string name, int fallback)
        {
            var a = (string)e.Attribute(name);
            return int.TryParse(a, NumberStyles.Integer, CultureInfo.InvariantCulture, out var n) ? n : fallback;
        }

        // ───────────────────────── 兼容取值 ─────────────────────────

        private static bool TryGetProp(JsonElement e, string name, out JsonElement value)
        {
            if (e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out value)) return true;
            // 有些站用大写键名
            if (e.ValueKind == JsonValueKind.Object)
            {
                foreach (var p in e.EnumerateObject())
                {
                    if (string.Equals(p.Name, name, StringComparison.OrdinalIgnoreCase))
                    {
                        value = p.Value;
                        return true;
                    }
                }
            }
            value = default;
            return false;
        }

        /// <summary>依次尝试多个候选键名，把数字/字符串统一成 string。</summary>
        private static string GetStringFlexible(JsonElement e, params string[] names)
        {
            foreach (var n in names)
            {
                if (!TryGetProp(e, n, out var v)) continue;
                switch (v.ValueKind)
                {
                    case JsonValueKind.String: return v.GetString();
                    case JsonValueKind.Number: return v.ToString();
                    case JsonValueKind.True: return "1";
                    case JsonValueKind.False: return "0";
                    case JsonValueKind.Null: continue;
                }
            }
            return null;
        }

        private static int? GetIntFlexible(JsonElement e, params string[] names)
        {
            var s = GetStringFlexible(e, names);
            if (s != null && int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out var n)) return n;
            return null;
        }
    }
}
