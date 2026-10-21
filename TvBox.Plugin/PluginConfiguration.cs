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

        /// <summary>同步开关：关掉后定时任务直接跳过。</summary>
        public bool EnableScheduledSync { get; set; }

        /// <summary>定时同步间隔（小时）。</summary>
        public int SyncIntervalHours { get; set; } = 12;

        /// <summary>
        /// 每个源的已知失效记录（"siteKey"），同步时跳过。源挂了是常态，别每次都白跑。
        /// </summary>
        public List<string> DeadSites { get; set; } = new List<string>();
    }
}
