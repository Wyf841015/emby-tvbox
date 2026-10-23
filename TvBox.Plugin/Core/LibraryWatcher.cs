using System;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Plugins;
using MediaBrowser.Model.Logging;

namespace TvBox.Plugin.Core
{
    /// <summary>
    /// Emby 媒体库删除监听：用户在 Emby 里删片 → 我们删掉对应的 strm 并移出清单。
    ///
    /// 为什么不用 Webhook 报文：Emby 的通知 JSON 结构随版本/事件类型变化
    /// （Path / Item.Path / Items[].Path 都见过），按字段解析必碎；
    /// 直接挂 ILibraryManager.ItemRemoved 事件，path 是强类型的，稳。
    ///
    /// 只处理输出目录 movies/tv 子树里的文件（StrmWriter.ForgetFile 内部再校验一次），
    /// 用户自己放进去的东西一律不碰。
    /// </summary>
    public class LibraryWatcher : IServerEntryPoint, IDisposable
    {
        private readonly ILibraryManager _library;
        private readonly ILogger _log;

        public LibraryWatcher(ILibraryManager library, ILogManager logManager)
        {
            _library = library;
            _log = logManager.GetLogger("TvBox");
        }

        public void Run()
        {
            try
            {
                _library.ItemRemoved += OnItemRemoved;
                _log.Info("[watch] 已挂载 Emby 媒体删除监听（删除同步开启时生效）");
                // 顺手保证 Webhook 令牌已生成（此时代插件的 Configuration 一定就绪）
                try { Plugin.Instance?.EnsureWebhookToken(); } catch { }
            }
            catch (Exception ex)
            {
                try { _log.Error("[watch] 挂载删除监听失败: " + ex.Message); } catch { }
            }
        }

        private void OnItemRemoved(object sender, ItemChangeEventArgs e)
        {
            try
            {
                var p = Plugin.Instance;
                if (p?.Configuration == null) return;
                if (!p.Configuration.EmbyDeleteSync) return;
                var path = e?.Item?.Path;
                if (string.IsNullOrWhiteSpace(path)) return;

                var w = new StrmWriter(p.ResolveOutputRoot(), m =>
                {
                    try { _log.Info("[watch] " + m); } catch { }
                });
                var ok = w.ForgetFile(path, out var msg);
                if (ok)
                    _log.Info("[watch] Emby 删除同步: " + path);
                else
                    _log.Debug("[watch] 跳过: " + path + " (" + msg + ")");
            }
            catch (Exception ex)
            {
                try { _log.Error("[watch] 处理删除事件异常: " + ex.Message); } catch { }
            }
        }

        public void Dispose()
        {
            try { _library.ItemRemoved -= OnItemRemoved; } catch { }
        }
    }
}
