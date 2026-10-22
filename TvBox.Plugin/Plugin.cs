using System;
using System.Collections.Generic;
using System.IO;
using MediaBrowser.Common;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Controller.Configuration;
using MediaBrowser.Common.Plugins;
using MediaBrowser.Model.Logging;
using MediaBrowser.Model.Plugins;
using MediaBrowser.Model.Serialization;
using TvBox.Plugin.Core;

namespace TvBox.Plugin
{
    /// <summary>
    /// 插件入口。Emby 通过反射发现实现 IPlugin 的类型并实例化，
    /// 构造函数里的参数由 Emby 的依赖注入容器提供。
    /// </summary>
    public class Plugin : BasePlugin<PluginConfiguration>, IHasWebPages
    {
        public static readonly Guid PluginId = Guid.Parse("7f3c1d92-4a6b-4c8e-9f21-8b5d3a72c104");

        private static Plugin _instance;

        private readonly IApplicationPaths _paths;
        private readonly IApplicationHost _host;
        private readonly ILogger _logger;
        private readonly IServerConfigurationManager _serverConfig;

        public Plugin(
            IApplicationPaths applicationPaths,
            IXmlSerializer xmlSerializer,
            ILogManager logManager,
            IApplicationHost applicationHost,
            IServerConfigurationManager serverConfigurationManager)
            : base(applicationPaths, xmlSerializer)
        {
            // 2 核小机器：抬高线程池下限。同步里存在同步等待（spider 调用最长 45s、
            // 海报下载），线程池被抽干时 Emby 的 Web 服务会整体失去响应，
            // 连「停止同步」的请求都排不进去 —— 用户看到的就是“停不下来”。
            try
            {
                System.Threading.ThreadPool.GetMinThreads(out var w, out var io);
                System.Threading.ThreadPool.SetMinThreads(Math.Max(w, 64), Math.Max(io, 64));
            }
            catch { }

            _instance = this;
            _paths = applicationPaths;
            _host = applicationHost;
            _serverConfig = serverConfigurationManager;
            _logger = logManager.GetLogger("TvBox");

            SyncService = new SyncService(msg => _logger.Info(msg));
            _logger.Info("TvBox 插件已加载");
        }

        public static Plugin Instance => _instance;

        public IApplicationHost ApplicationHost => _host;

        /// <summary>
        /// plugins 目录的真实路径。插件自更新要用它定位自己的 DLL：
        /// Emby 是用 Assembly.Load(byte[]) 加载插件的，所以 Assembly.Location 是空串，
        /// 只能从宿主这里拿（IApplicationPaths.PluginsPath）。
        /// </summary>
        public string PluginsPath => _paths?.PluginsPath;

        public SyncService SyncService { get; }

        public ILogger Logger => _logger;

        public override string Name => "TvBox For Emby";

        public override string Description =>
            "把 TVBox 配置里的点播源（苹果 CMS 接口）同步成 .strm 媒体库，让 Emby 能浏览、搜索、播放。";

        public override Guid Id => PluginId;

        /// <summary>
        /// 必须显式给出配置文件文件名。BasePlugin 的默认实现返回 null，
        /// 而 ConfigurationFilePath 会 Path.Combine(PluginConfigurationsPath, null) → 直接抛
        /// ArgumentNullException，表现为插件「创建实例失败」加载不起来。
        /// </summary>
        public override string ConfigurationFileName => "TvBox.xml";

        /// <summary>
        /// STRM 输出根目录。用户没指定时放在 Emby 的数据目录下，
        /// 这样升级 Emby / 插件都不会把文件冲掉。
        /// </summary>
        public string ResolveOutputRoot()
        {
            var cfg = Configuration;
            if (cfg != null && !string.IsNullOrWhiteSpace(cfg.OutputRoot))
                return cfg.OutputRoot.Trim();

            var baseDir = _paths?.DataPath;
            if (string.IsNullOrWhiteSpace(baseDir))
                baseDir = Path.Combine(Path.GetTempPath(), "emby-tvbox");
            return Path.Combine(baseDir, "tvbox");
        }

        /// <summary>
        /// 用于把播放地址改写成本机代理地址的基地址。
        /// 用户没填就返回 null（此时 strm 里写原始地址）。
        /// </summary>
        /// <summary>
        /// strm 里的播放代理地址前缀。
        /// 用户没填就自动用「本机回环 + Emby 实际监听端口」——插件跑在 Emby 进程里，
        /// 回环一定通，比让人手填 IP 靠谱得多（填错就整片黑屏）。
        /// </summary>
        public string ResolveEmbyBaseUrl()
        {
            var cfg = Configuration;
            if (cfg != null && !string.IsNullOrWhiteSpace(cfg.EmbyBaseUrl))
                return cfg.EmbyBaseUrl.Trim().TrimEnd('/');

            int p2 = 8096;
            try
            {
                var port = _serverConfig?.Configuration?.HttpServerPortNumber ?? 0;
                if (port > 0) p2 = port;
            }
            catch { }

            // 关键：不能写 127.0.0.1。
            // strm 是交给客户端（电视/手机/VidHub）去播的，客户端拿到 127.0.0.1 会去连它自己，
            // 必然播不了。这里自动挑本机可被局域网访问的 IPv4。
            var lan = DetectLanAddress();
            if (lan != null) return "http://" + lan + ":" + p2;

            // 找不到局域网地址时返回 null，绝不退化成 127.0.0.1：
            // 那会静默写出一整库客户端连自己的死链，而且日志一个字都不提。
            // 交给 SyncService 明确拒绝并提示用户去填「Emby 访问地址」。
            return null;
        }

        /// <summary>
        /// 找一个局域网可达的本机 IPv4：跳过回环、链路本地、docker/veth 网桥。
        /// 优先 192.168/10./172.16-31 这类私网地址。
        /// </summary>
        public static string DetectLanAddress()
        {
            try
            {
                var fallback172 = new System.Collections.Generic.List<System.Net.IPAddress>();
                var candidates = new System.Collections.Generic.List<System.Net.IPAddress>();
                foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                    var name = (ni.Name ?? "").ToLowerInvariant();
                    if (name.StartsWith("docker") || name.StartsWith("veth") || name.StartsWith("br-")
                        || name.StartsWith("virbr") || name.StartsWith("cni") || name.StartsWith("flannel")
                        || name.StartsWith("tun") || name.StartsWith("wg")) continue;

                    foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                    {
                        var ip = ua.Address;
                        if (ip.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) continue;
                        var b = ip.GetAddressBytes();
                        if (b[0] == 127) continue;                                  // 回环
                        if (b[0] == 169 && b[1] == 254) continue;                   // 链路本地
                        if (b[0] == 10 && b[1] == 88) continue;                     // 常见 overlay
                        // 172.16/12 里有 docker 网桥，但也是真实 LAN 的常用段。
                        // 直接整段排除会让这些用户的 ResolveEmbyBaseUrl 返回 null → 同步中止。
                        // 改成「降级候选」：有 192.168/10 时优先用它们，没有才退回这里。
                        if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) { fallback172.Add(ip); continue; }
                        candidates.Add(ip);
                    }
                }

                if (candidates.Count == 0) candidates.AddRange(fallback172);

                // 私网优先，其次随便一个
                foreach (var ip in candidates)
                {
                    var b = ip.GetAddressBytes();
                    bool priv = b[0] == 192 && b[1] == 168
                             || b[0] == 10
                             || (b[0] == 172 && b[1] >= 16 && b[1] <= 31);
                    if (priv) return ip.ToString();
                }
                return candidates.Count > 0 ? candidates[0].ToString() : null;
            }
            catch
            {
                return null;
            }
        }

        public IEnumerable<PluginPageInfo> GetPages()
        {
            return new[]
            {
                new PluginPageInfo
                {
                    Name = "tvbox",
                    EmbeddedResourcePath = "TvBox.Plugin.Configuration.Web.config.html",
                    IsMainConfigPage = true,
                    EnableInMainMenu = false,
                },
            };
        }
    }
}
