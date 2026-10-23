using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace TvBox.Plugin.Core
{
    /// <summary>
    /// 增量同步状态：记住每个条目上次同步的时间、列表侧指纹、以及它写出来的文件。
    ///
    /// 为什么需要它：一轮全量同步要拉 40~100 个站 × 多页列表 × 每条详情，慢且容易
    /// 触发源站限流。实际上大部分条目在一个同步周期内根本没变（新集才变）。
    /// 这里用「列表侧指纹」（名称 + 备注 + 年份）做判据：
    ///   · 指纹没变 且 距上次同步 &lt; IncrementalHours  → 连详情都不用拉，
    ///     把上次写出的文件列表直接并进 keep，等于「确认它还在、且不用改」
    ///   · 指纹变了（比如备注从「更新至12集」变成「更新至14集」）→ 正常拉详情补新集
    ///
    /// 注意：keep 里必须放真实存在的文件，否则清理逻辑会把它们当孤儿删掉。
    /// 所以这里对每条缓存的路径都做一次 File.Exists 校验。
    /// </summary>
    public class SyncState
    {
        public class Entry
        {
            [JsonPropertyName("at")] public long At { get; set; }          // Unix 秒
            [JsonPropertyName("sig")] public string Sig { get; set; } = ""; // 列表侧指纹
            [JsonPropertyName("paths")] public List<string> Paths { get; set; } = new List<string>();
            /// <summary>记录时源侧的 vod_time（增量游标：源更新了就别再跳过）。</summary>
            [JsonPropertyName("t")] public string Time { get; set; } = "";
        }

        private readonly string _path;
        private readonly Action<string> _log;
        private Dictionary<string, Entry> _items = new Dictionary<string, Entry>(StringComparer.Ordinal);
        private bool _dirty;
        private int _sinceSave;

        public int Count => _items.Count;
        public int SkippedHits { get; private set; }

        private SyncState(string path, Action<string> log)
        {
            _path = path;
            _log = log ?? (_ => { });
        }

        public static SyncState Load(string outputRoot, Action<string> log)
        {
            var state = new SyncState(Path.Combine(outputRoot, ".tvbox-state.json"), log);
            try
            {
                if (File.Exists(state._path))
                {
                    var json = File.ReadAllText(state._path, Encoding.UTF8);
                    var map = JsonSerializer.Deserialize<Dictionary<string, Entry>>(json);
                    if (map != null) state._items = new Dictionary<string, Entry>(map, StringComparer.Ordinal);
                }
            }
            catch (Exception ex)
            {
                log?.Invoke("[state] 读增量状态失败（当作首次同步）: " + ex.Message);
            }
            return state;
        }

        private static string Key(string siteKey, string id) => (siteKey ?? "") + "|" + (id ?? "");

        public static string Signature(VodItem item)
        {
            if (item == null) return "";
            return (item.Name ?? "") + "\u0001" + (item.Remarks ?? "") + "\u0001" + (item.Year ?? "");
        }

        /// <summary>
        /// 能不能跳过这个条目。返回 true 时 paths 里是上次写出来的、**当前仍然存在**的文件。
        /// </summary>
        public bool TrySkip(string siteKey, string id, string sig, int hours, out List<string> paths,
                            string srcTime = null, bool useTimeCursor = false)
        {
            paths = null;
            if (hours <= 0 || string.IsNullOrEmpty(id)) return false;
            if (!_items.TryGetValue(Key(siteKey, id), out var e)) return false;
            if (!string.Equals(e.Sig, sig, StringComparison.Ordinal)) return false;
            // 源侧更新时间变了 → 有新集/换线路，必须重新拉详情
            if (useTimeCursor && !string.IsNullOrWhiteSpace(srcTime)
                && !string.Equals(e.Time ?? "", srcTime.Trim(), StringComparison.Ordinal))
                return false;

            var age = DateTimeOffset.UtcNow.ToUnixTimeSeconds() - e.At;
            if (age < 0 || age > hours * 3600L) return false;

            var alive = (e.Paths ?? new List<string>()).Where(File.Exists).ToList();
            if (alive.Count == 0) return false;      // 文件被删了，当作没同步过

            paths = alive;
            SkippedHits++;
            return true;
        }

        public void Record(string siteKey, string id, string sig, IEnumerable<string> paths, string srcTime = null)
        {
            if (string.IsNullOrEmpty(id)) return;
            _items[Key(siteKey, id)] = new Entry
            {
                At = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                Sig = sig ?? "",
                Time = (srcTime ?? "").Trim(),
                Paths = (paths ?? Enumerable.Empty<string>()).Where(p => !string.IsNullOrEmpty(p)).ToList(),
            };
            _dirty = true;
            if (++_sinceSave >= 25) { _sinceSave = 0; Save(); }   // 长任务定期落盘
        }

        /// <summary>清掉超过 retainDays 天的记录，防止状态文件无限膨胀。</summary>
        public void Prune(int retainDays = 30)
        {
            var cutoff = DateTimeOffset.UtcNow.ToUnixTimeSeconds() - retainDays * 86400L;
            var dead = _items.Where(kv => kv.Value.At < cutoff).Select(kv => kv.Key).ToList();
            foreach (var k in dead) _items.Remove(k);
            if (dead.Count > 0) _dirty = true;
        }

        public void Save(bool force = false)
        {
            if (!_dirty && !force) return;
            try
            {
                var dir = Path.GetDirectoryName(_path);
                if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
                var json = JsonSerializer.Serialize(_items,
                    new JsonSerializerOptions { WriteIndented = false });
                File.WriteAllText(_path, json, new UTF8Encoding(false));
                _dirty = false;
            }
            catch (Exception ex)
            {
                _log("[state] 保存增量状态失败: " + ex.Message);
            }
        }
    }
}
