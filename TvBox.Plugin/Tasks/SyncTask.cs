using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Logging;
using MediaBrowser.Model.Tasks;
using TvBox.Plugin.Core;

namespace TvBox.Plugin.Tasks
{
    /// <summary>
    /// 定时同步任务。在 Emby 后台的「计划任务」里能看到并手动运行。
    /// </summary>
    public class SyncTask : IScheduledTask
    {
        private readonly ILogger _logger;

        public SyncTask(ILogManager logManager)
        {
            _logger = logManager.GetLogger("TvBox.Sync");
        }

        public string Name => "TVBox – 同步源到媒体库";

        public string Description =>
            "读取 TVBox 配置，把点播源里能解析的条目写成 .strm 文件，供 Emby 扫描、搜索和播放。";

        public string Category => "TVBox";

        public string Key => "TvBoxSyncAll";

        public IEnumerable<TaskTriggerInfo> GetDefaultTriggers()
        {
            var cfg = Plugin.Instance?.Configuration;
            if (cfg == null || !cfg.EnableScheduledSync)
                return Array.Empty<TaskTriggerInfo>();

            return new[]
            {
                new TaskTriggerInfo
                {
                    // Emby 的 TaskTriggerInfo.Type 是字符串，不是枚举
                    Type = "IntervalTrigger",
                    IntervalTicks = TimeSpan.FromHours(Math.Max(1, cfg.SyncIntervalHours)).Ticks,
                },
            };
        }

        public async Task Execute(CancellationToken cancellationToken, IProgress<double> progress)
        {
            var plugin = Plugin.Instance;
            if (plugin == null) return;

            var cfg = plugin.Configuration;
            if (string.IsNullOrWhiteSpace(cfg.ConfigUrl))
            {
                _logger.Info("没有配置 TVBox 配置地址，跳过同步");
                return;
            }

            var outputRoot = plugin.ResolveOutputRoot();
            _logger.Info($"开始同步，输出目录: {outputRoot}");

            progress?.Report(0);

            var ran = await plugin.SyncService.RunAsync(
                cfg,
                outputRoot,
                cancellationToken,
                () => plugin.SaveConfiguration(),
                plugin.ResolveEmbyBaseUrl()).ConfigureAwait(false);

            if (!ran)
            {
                _logger.Info("已有同步在运行，本次跳过");
                return;
            }

            progress?.Report(95);

            // 告诉 Emby 这两个目录变了，让它增量扫描（比整库扫描快很多）
            RefreshLibrary(plugin, outputRoot);

            progress?.Report(100);
        }

        /// <summary>
        /// 通知 Emby 媒体库监视器。Emby 的 ILibraryMonitor 是内部服务，
        /// 要通过 IApplicationHost.Resolve 拿；某些版本/环境下可能拿不到，降级为等下一次扫描。
        /// </summary>
        private void RefreshLibrary(Plugin plugin, string outputRoot)
        {
            try
            {
                var host = plugin.ApplicationHost;
                if (host == null) return;

                // IApplicationHost 只提供泛型 Resolve<T>()（已从 DLL 反射确认）
                var monitor = host.Resolve<ILibraryMonitor>();
                if (monitor == null)
                {
                    _logger.Warn("ILibraryMonitor 不可用，本次不主动刷新，等 Emby 的计划扫描");
                    return;
                }

                foreach (var sub in new[] { "movies", "tv" })
                {
                    var path = System.IO.Path.Combine(outputRoot, sub);
                    if (!System.IO.Directory.Exists(path)) continue;
                    try
                    {
                        monitor.ReportFileSystemChanged(path);
                        _logger.Info("已通知 Emby 刷新: " + path);
                    }
                    catch (Exception ex)
                    {
                        _logger.Warn($"刷新 {path} 失败: {ex.Message}");
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.Warn("刷新媒体库失败: " + ex.Message);
            }
        }
    }
}
