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

            // 配了多计划时每 5 分钟醒一次（用来匹配 HH:mm），否则按小时间隔跑
            var hasPlans = !string.IsNullOrWhiteSpace(cfg.SyncPlans);
            return new[]
            {
                new TaskTriggerInfo
                {
                    // Emby 的 TaskTriggerInfo.Type 是字符串，不是枚举
                    Type = "IntervalTrigger",
                    IntervalTicks = hasPlans
                        ? TimeSpan.FromMinutes(5).Ticks
                        : TimeSpan.FromHours(Math.Max(1, cfg.SyncIntervalHours)).Ticks,
                },
            };
        }

        /// <summary>
        /// 多计划调度：每行一条「HH:mm|站点key1,站点key2|full|incremental」。
        /// 站点留空 = 全部；第三段 full = 本次强制全量（临时关掉增量）。
        /// 同一条计划当天同一分钟只触发一次（记在 SyncPlanLastRun）。
        /// 返回 null = 现在不该跑。
        /// </summary>
        private string PickDuePlan(PluginConfiguration cfg)
        {
            if (string.IsNullOrWhiteSpace(cfg.SyncPlans)) return null;
            var now = DateTime.Now;
            var hhmm = now.ToString("HH:mm");
            var stamp = now.ToString("yyyyMMddHHmm");
            foreach (var raw in cfg.SyncPlans.Replace("\r\n", "\n").Split('\n'))
            {
                var line = raw.Trim();
                if (line.Length == 0 || line.StartsWith("#")) continue;
                var parts = line.Split('|');
                var when = parts.Length > 0 ? parts[0].Trim() : "";
                if (when.Length != 5 || when != hhmm) continue;
                if (cfg.GetPlanLastRun(line) == stamp) continue;
                // 同一分钟只跑一条
                cfg.SetPlanLastRun(line, stamp);
                return line;
            }
            return null;
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

            // ── 多计划：命中哪条就按哪条的站点子集/模式跑一轮 ──
            var plan = PickDuePlan(cfg);
            var savedWhite = cfg.SiteWhitelist;
            var savedInc = cfg.IncrementalSync;
            if (plan != null)
            {
                var parts = plan.Split('|');
                var sites = parts.Length > 1 ? parts[1].Trim() : string.Empty;
                var mode = parts.Length > 2 ? parts[2].Trim().ToLowerInvariant() : string.Empty;
                if (sites.Length > 0) cfg.SiteWhitelist = sites;
                if (mode == "full") cfg.IncrementalSync = false;
                _logger.Info($"[计划] 命中「{plan}」：站点={(sites.Length > 0 ? sites : "全部")} 模式={(mode.Length > 0 ? mode : "默认")}");
            }
            else if (!string.IsNullOrWhiteSpace(cfg.SyncPlans))
            {
                _logger.Info("该时刻没有到期的同步计划，跳过");
                return;
            }

            var outputRoot = plugin.ResolveOutputRoot();
            _logger.Info($"开始同步，输出目录: {outputRoot}");

            progress?.Report(0);

            bool ran;
            try
            {
                ran = await plugin.SyncService.RunAsync(
                    cfg,
                    outputRoot,
                    cancellationToken,
                    () => plugin.SaveConfiguration(),
                    plugin.ResolveEmbyBaseUrl()).ConfigureAwait(false);
            }
            finally
            {
                // 计划里的临时覆盖必须还原，否则会污染用户配置
                cfg.SiteWhitelist = savedWhite;
                cfg.IncrementalSync = savedInc;
            }

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

                // 目录现在是「站点/movies|tv」，逐个通知；旧的根下 movies/tv 也顺带通知
                var targets = new System.Collections.Generic.List<string>();
                try
                {
                    foreach (var site in System.IO.Directory.GetDirectories(outputRoot))
                    {
                        var n = System.IO.Path.GetFileName(site);
                        if (string.IsNullOrEmpty(n) || n.StartsWith(".")) continue;
                        foreach (var sub in new[] { "movies", "tv" })
                        {
                            var p = System.IO.Path.Combine(site, sub);
                            if (System.IO.Directory.Exists(p)) targets.Add(p);
                        }
                    }
                }
                catch (Exception ex) { _logger.Warn("枚举站点目录失败: " + ex.Message); }
                foreach (var sub in new[] { "movies", "tv" })
                {
                    var p = System.IO.Path.Combine(outputRoot, sub);
                    if (System.IO.Directory.Exists(p)) targets.Add(p);
                }
                foreach (var path in targets)
                {
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
