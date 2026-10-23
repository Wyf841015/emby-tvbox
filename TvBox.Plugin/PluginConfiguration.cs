using System;
using System.Collections.Generic;
using MediaBrowser.Model.Plugins;

namespace TvBox.Plugin
{
    /// <summary>
    /// 插件配置。字段类型尽量用基元 + List，保证 Emby 的 XmlSerializer 能直接序列化。
    /// </summary>
    public class PluginConfiguration : BasePluginConfiguration
    {
        /// <summary>
        /// TVBox 配置地址。支持三种写法：
        ///   1) 单仓 JSON 的 http(s) 地址
        ///   2) 多仓地址（返回 {"urls":[{"url":"..."}]}）—— 会自动展开
        ///   3) 本地文件绝对路径（/vol1/xxx/config.json）
        /// 内容本身是 base64 的也能自动识别。
        /// </summary>
        public string ConfigUrl { get; set; } = string.Empty;

        /// <summary>
        /// spider-hub 中转地址，例如 http://127.0.0.1:8098。
        /// 填了之后，配置里 type:3 + api:csp_XXX 的 spider 站点会被自动改写成
        /// &lt;hub&gt;/v/&lt;key&gt;/api.php/provide/vod/ 当作普通采集站同步。
        /// 留空 = 跳过所有 spider 站点。
        /// </summary>
        public string SpiderHubUrl { get; set; } = string.Empty;

        /// <summary>
        /// 只同步这些站点的 key（逗号分隔）。留空 = 同步全部受支持的站点。
        /// </summary>
        public string SiteWhitelist { get; set; } = string.Empty;

        /// <summary>
        /// 排除这些站点的 key（逗号分隔）。优先生效。
        /// </summary>
        public string SiteBlacklist { get; set; } = string.Empty;

        /// <summary>
        /// 只看这些名字的站点（模糊匹配，逗号分隔）。留空 = 不限。
        /// </summary>
        public string SiteNameFilter { get; set; } = string.Empty;

        /// <summary>
        /// 给 spider-hub 用的共享令牌。
        /// hub 装插件时会写进这份配置，之后 hub 调 /TvBox/Sync、/TvBox/Status
        /// 这些免登录路由都要带 ?token=xxx，避免局域网里谁都能触发同步。
        /// 留空 = 不校验（兼容旧配置）。
        /// </summary>
        public string HubToken { get; set; } = string.Empty;

        /// <summary>
        /// 解析源时直接排除网盘类源（默认 true）。
        /// 网盘类 spider 都继承 catvod 的 Pan 基类：能把分享页列出来，但分享链接换直链
        /// 需要夸克/115/迅雷/百度等账号，没有账号永远播不了，只会让同步白跑。
        /// 判定优先问 spider-hub（它用 JVM 继承链判，准）；hub 连不上时用名字/类名兜底。
        /// </summary>
        public bool ExcludeCloudDrive { get; set; } = true;

        /// <summary>
        /// 写入 strm 前先校验播放地址是否真的能取到（默认 true）。
        /// 很多源的播放列表里塞的是 .png 广告分片、或者干脆 404，
        /// 不校验就会出现"文件生成了一堆，个个播不了"。
        /// </summary>
        public bool VerifyPlayUrl { get; set; } = true;

        /// <summary>STRM 输出根目录。留空则用 Emby 数据目录下的 tvbox/。</summary>
        public string OutputRoot { get; set; } = string.Empty;

        /// <summary>每个分类最多抓多少页（每页通常 20 条）。</summary>
        public int MaxPagesPerCategory { get; set; } = 3;

        /// <summary>单次同步最多写多少个条目（0 = 不限，调试用）。</summary>
        public int MaxItemsPerRun { get; set; } = 0;

        /// <summary>并发抓取数。TVBox 源普遍脆弱，开太大容易被封。</summary>
        public int Concurrency { get; set; } = 3;

        /// <summary>每个请求之间的最小延迟（毫秒）。</summary>
        public int RequestDelayMs { get; set; } = 150;

        /// <summary>HTTP 超时（秒）。</summary>
        public int TimeoutSeconds { get; set; } = 15;

        /// <summary>抓取时使用的 User-Agent。</summary>
        public string UserAgent { get; set; } =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

        /// <summary>
        /// 本机 Emby 的访问地址，例如 http://192.168.1.10:8096。
        /// 开启代理时必须填，否则 strm 里没法写绝对地址。
        /// </summary>
        public string EmbyBaseUrl { get; set; } = string.Empty;

        /// <summary>播放时使用的 Referer。很多采集站的 m3u8 校验这个。</summary>
        public string Referer { get; set; } = string.Empty;

        /// <summary>
        /// 是否把播放地址改写成本插件的代理地址（/TvBox/Stream?u=...）。
        /// 打开后能解决防盗链 / 需要特定 Referer 的源，代价是所有流量都过 Emby。
        /// </summary>
        public bool UseProxy { get; set; } = true;

        /// <summary>
        /// strm 里写什么地址（外网能不能播就看这个）：
        ///   proxy           —— 本插件 /TvBox/Play（内网可播；外网需能免认证访问到 Emby）
        ///   direct          —— 源站直链（外网直接可播；链接会过期，按 DirectRefreshHours 自动重解析）
        ///   direct_fallback —— 同 direct，解析不出来的那一集退回 proxy 地址
        /// </summary>
        public string StrmPlayMode { get; set; } = "proxy";

        /// <summary>
        /// 播放地址基址（写进 strm 的协议+主机），留空=用 EmbyBaseUrl。
        /// 域名分流场景填域名：家里 DNS 指内网、外网指公网，一份 strm 内外通吃。
        /// </summary>
        public string StreamBaseUrl { get; set; } = "";

        /// <summary>直链模式多久重解析一次（小时）。源站 auth_key 通常 1~3 天过期。</summary>
        public int DirectRefreshHours { get; set; } = 12;

        /// <summary>
        /// 允许代理到内网/本机地址。默认关闭（防 SSRF）。
        /// 自建 CMS 源在内网时打开，否则播放会被「blocked host」拒掉。
        /// </summary>
        public bool AllowPrivateHosts { get; set; }

        /// <summary>同步时是否清理已不在源里的 strm 文件。</summary>
        public bool CleanupOrphans { get; set; } = true;

        /// <summary>上次同步时间（UTC）。</summary>
        public DateTime LastSyncUtc { get; set; } = DateTime.MinValue;

        /// <summary>上次同步的摘要，给配置页展示。</summary>
        public string LastSyncSummary { get; set; } = string.Empty;

        /// <summary>
        /// 批量拉详情时每批的 id 个数（默认 16）。
        /// 一批里只要有一个 spider 卡住就会整批超时，所以代码会自动对半拆开重试；
        /// 这个值越大请求越少、但单批失败时丢的范围也越大。
        /// </summary>
        public int DetailBatchSize { get; set; } = 16;

        /// <summary>
        /// 单个条目最多校验多少集（默认 48）。源里动辄 200+ 集，每集要 2 个请求，
        /// 全量校验一个条目就能烧掉 500+ 次请求；超过这个数就做样本校验
        /// （首 12 + 尾 12 + 中间均匀抽），没抽到的按可播写入。填 0 = 不限（很慢）。
        /// </summary>
        public int VerifyMaxEpisodes { get; set; } = 48;

        /// <summary>
        /// 把「不能直接播」的地址解成真媒体地址（默认开）：
        /// 解析接口（?url=…）、网页播放页、302 中转。解的只是被校验的那几集，
        /// 解不出来就照旧跳过，不会让同步变慢。
        /// </summary>
        public bool ResolveIndirectUrl { get; set; } = true;

        /// <summary>同步开关：关掉后定时任务直接跳过。</summary>
        public bool EnableScheduledSync { get; set; }

        /// <summary>定时同步间隔（小时）。</summary>
        public int SyncIntervalHours { get; set; } = 12;

        /// <summary>
        /// 每个源的已知失效记录（"siteKey"），同步时跳过。源挂了是常态，别每次都白跑。
        /// </summary>
        /// <summary>写 nfo：把源站的简介/类型/地区/演员写成 movie.nfo / tvshow.nfo。Emby 优先读本地元数据，按中文名刮削失败也不会没简介没海报</summary>
        public bool WriteNfo { get; set; } = true;

        /// <summary>下载海报：把源站封面存成条目目录下的 poster.jpg</summary>
        public bool DownloadPoster { get; set; } = true;

        /// <summary>海报大小上限（MB），超过就跳过</summary>
        public int PosterMaxMb { get; set; } = 4;

        /// <summary>增量同步：列表侧信息（名称/备注/年份）没变且在上次同步有效期内的条目，直接跳过详情请求</summary>
        public bool IncrementalSync { get; set; } = true;

        /// <summary>增量有效期（小时）：超过这个时间就重新拉一次详情，保证新集能补上</summary>
        public int IncrementalHours { get; set; } = 12;

        /// <summary>播放模式：proxy=插件代理转发（默认，能补 Referer/UA、能重写 HLS 分片）；redirect=302 直链不占 NAS 带宽；auto=按下方域名清单判断，m3u8 一律走代理</summary>
        public string StreamMode { get; set; } = "proxy";

        /// <summary>302 直链域名清单（逗号分隔，可留空）。auto 模式下命中的域名走 302，其余走代理</summary>
        public string RedirectHosts { get; set; } = string.Empty;

        /// <summary>单次同步最多删除多少个 strm（防误删保险，0 = 不限）</summary>
        public int MaxDeletePerRun { get; set; } = 300;

        public List<string> DeadSites { get; set; } = new List<string>();

        // ───────────── 播放链路（v0.2.0 新增）─────────────

        /// <summary>
        /// 播放直链缓存时长（分钟，0 = 关闭）。同步的逐集校验会顺手预热缓存，
        /// 之后重播/换客户端/拖动重连直接命中，不用再打源站解析。
        /// </summary>
        public int PlayCacheMinutes { get; set; } = 25;

        /// <summary>
        /// strm 里播放地址的签名密钥（留空 = 自动生成）。
        /// 配了之后 /TvBox/Stream 会校验 HMAC，防止有人拿到代理地址白嫖你的 NAS 刷源站。
        /// </summary>
        public string StreamSign { get; set; } = string.Empty;

        /// <summary>
        /// true = 没签名或签名不对一律拒绝。把 Emby 暴露到公网时务必打开；
        /// 内网使用可以关（改密钥后老 strm 不用重同步）。
        /// </summary>
        public bool StreamSignRequired { get; set; }

        // ───────────── 命名与过滤（v0.2.0 新增）─────────────

        /// <summary>
        /// 条目命名模板。占位符：{name} 片名、{year} 年份、{remarks} 备注、{site} 站点名。
        /// 默认 "{name} ({year})"，与 Emby 规范一致（电影目录名 = 文件名）。
        /// </summary>
        public string NameTemplate { get; set; } = "{name} ({year})";

        /// <summary>
        /// 重命名规则（每行一条，格式 正则=>替换）。按顺序作用在片名上，
        /// 用来清掉源站标题里的【高清】【中字】【未删减】这类装饰。
        /// 例：\[.*?\]=>   或   【.*?】=>
        /// </summary>
        public string RenameRules { get; set; } = string.Empty;

        /// <summary>命中这些关键词的条目不写入（逗号分隔，如 预告,花絮,抢先版）</summary>
        public string SkipKeywords { get; set; } = string.Empty;

        // ───────────── 元数据增强（v0.2.0 新增）─────────────

        /// <summary>TMDB API Key（可选，留空 = 不做规范化）。填了会用「片名+年份」查 TMDB，写规范名与 tmdbid，显著提高刮削命中率</summary>
        public string TmdbApiKey { get; set; } = string.Empty;

        /// <summary>TMDB 语言（默认 zh-CN，中文名优先）</summary>
        public string TmdbLanguage { get; set; } = "zh-CN";

        /// <summary>落地源站提供的字幕（如果源里有 srt/ass/vtt 地址）</summary>
        public bool DownloadSubtitle { get; set; } = true;

        // ───────────── 增量与调度（v0.2.0 新增）─────────────

        /// <summary>
        /// 按条目更新时间戳增量（默认开）：只在 IncrementalHours 窗口内 且 vod_time 也没变时才跳过，
        /// 避免纯时间窗口既漏更又白扫。
        /// </summary>
        public bool UseItemTimeCursor { get; set; } = true;

        /// <summary>
        /// 多条同步计划（每行一条）：时:分|站点key1,站点key2|full|incremental
        /// 例  03:00||full   每天 3 点全量；每 6 小时那条用 SyncIntervalHours。
        /// 留空 = 只用 SyncIntervalHours 的等间隔计划。
        /// </summary>
        public string SyncPlans { get; set; } = string.Empty;

        /// <summary>站点连续失败多少次后自动移出同步（默认 5，0 = 不熔断）</summary>
        public int SiteFailThreshold { get; set; } = 5;

        /// <summary>
        /// Webhook 令牌（自动生成）：外部工具/Emby 通知用
        /// &lt;Emby 地址&gt;/TvBox/Webhook?token=… 触发生成或删除同步。
        /// </summary>
        public string WebhookToken { get; set; } = string.Empty;

        /// <summary>Emby 里删除影片时，同步删掉我们的 strm 与清单记录（默认开）</summary>
        public bool EmbyDeleteSync { get; set; } = true;

        /// <summary>
        /// 每条计划上次触发的时间（"计划行=yyyyMMddHHmm"，内部状态）。
        /// 用 List&lt;string&gt; 而不是 Dictionary：Emby 的 XmlSerializer 不支持 Dictionary，
        /// 加了 Dictionary 会让整份配置保存失败（"error reflecting type"）。
        /// </summary>
        public List<string> SyncPlanLastRun { get; set; } = new List<string>();

        /// <summary>每个站点的连续失败次数（"站点key=次数"，内部状态）。同样不能用 Dictionary</summary>
        public List<string> SiteFailStreak { get; set; } = new List<string>();

        // ── List 形式的存取助手（XmlSerializer 只序列化属性，方法无所谓）──
        public int GetFailStreak(string key)
        {
            if (string.IsNullOrEmpty(key) || SiteFailStreak == null) return 0;
            var prefix = key + "=";
            foreach (var it in SiteFailStreak)
            {
                if (it == null || !it.StartsWith(prefix, StringComparison.Ordinal)) continue;
                int v;
                if (int.TryParse(it.Substring(prefix.Length), out v)) return v;
            }
            return 0;
        }

        public int BumpFailStreak(string key)
        {
            if (string.IsNullOrEmpty(key)) return 0;
            if (SiteFailStreak == null) SiteFailStreak = new List<string>();
            var now = GetFailStreak(key) + 1;
            SetFailStreak(key, now);
            return now;
        }

        public void SetFailStreak(string key, int value)
        {
            if (string.IsNullOrEmpty(key)) return;
            if (SiteFailStreak == null) SiteFailStreak = new List<string>();
            for (int i = 0; i < SiteFailStreak.Count; i++)
            {
                if (SiteFailStreak[i] != null && SiteFailStreak[i].StartsWith(key + "=", StringComparison.Ordinal))
                {
                    SiteFailStreak[i] = key + "=" + value;
                    return;
                }
            }
            SiteFailStreak.Add(key + "=" + value);
        }

        public void ResetFailStreak(string key)
        {
            if (string.IsNullOrEmpty(key) || SiteFailStreak == null) return;
            SiteFailStreak.RemoveAll(it => it != null && it.StartsWith(key + "=", StringComparison.Ordinal));
        }

        public string GetPlanLastRun(string planLine)
        {
            if (string.IsNullOrEmpty(planLine) || SyncPlanLastRun == null) return null;
            var prefix = planLine + "=";
            foreach (var it in SyncPlanLastRun)
                if (it != null && it.StartsWith(prefix, StringComparison.Ordinal)) return it.Substring(prefix.Length);
            return null;
        }

        public void SetPlanLastRun(string planLine, string stamp)
        {
            if (string.IsNullOrEmpty(planLine)) return;
            if (SyncPlanLastRun == null) SyncPlanLastRun = new List<string>();
            for (int i = 0; i < SyncPlanLastRun.Count; i++)
            {
                if (SyncPlanLastRun[i] != null && SyncPlanLastRun[i].StartsWith(planLine + "=", StringComparison.Ordinal))
                {
                    SyncPlanLastRun[i] = planLine + "=" + stamp;
                    return;
                }
            }
            SyncPlanLastRun.Add(planLine + "=" + stamp);
        }
    }
}
