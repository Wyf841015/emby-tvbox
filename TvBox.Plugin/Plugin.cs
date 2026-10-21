using System;
using System.Collections.Generic;
using System.IO;
using MediaBrowser.Common;
using MediaBrowser.Common.Configuration;
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

        public Plugin(
            IApplicationPaths applicationPaths,
            IXmlSerializer xmlSerializer,
            ILogManager logManager,
            IApplicationHost applicationHost)
            : base(applicationPaths, xmlSerializer)
        {
            _instance = this;
            _paths = applicationPaths;
            _host = applicationHost;
            _logger = logManager.GetLogger("TvBox");

            SyncService = new SyncService(msg => _logger.Info(msg));
            _logger.Info("TvBox 插件已加载");
        }

        public static Plugin Instance => _instance;

        public IApplicationHost ApplicationHost => _host;

        public SyncService SyncService { get; }

        public ILogger Logger => _logger;

        public override string Name => "TVBox 源接入";

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
        public string ResolveEmbyBaseUrl()
        {
            var cfg = Configuration;
            if (cfg == null || string.IsNullOrWhiteSpace(cfg.EmbyBaseUrl)) return null;
            return cfg.EmbyBaseUrl.Trim().TrimEnd('/');
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
