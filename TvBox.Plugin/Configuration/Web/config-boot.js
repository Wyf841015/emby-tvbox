(function () {
    'use strict';
    if (window.__tvboxBooted) return;
    window.__tvboxBooted = true;

    var PLUGIN_ID = '7f3c1d92-4a6b-4c8e-9f21-8b5d3a72c104';
    var TOKEN = '';

    function el(id) { return document.getElementById(id); }
    function setText(id, t) { var e = el(id); if (e) e.textContent = t; }

    // Emby 的 ApiClient 可能在 window / parent / top（iframe 场景），也可能压根拿不到
    function apiClient() {
        try { if (window.ApiClient) return window.ApiClient; } catch (e) { }
        try { if (window.parent && window.parent.ApiClient) return window.parent.ApiClient; } catch (e) { }
        try { if (window.top && window.top.ApiClient) return window.top.ApiClient; } catch (e) { }
        return null;
    }

    var TEXT_MAP = {
        configUrl: 'ConfigUrl', outputRoot: 'OutputRoot', embyBaseUrl: 'EmbyBaseUrl',
        spiderHubUrl: 'SpiderHubUrl', referer: 'Referer',
        siteWhitelist: 'SiteWhitelist', siteBlacklist: 'SiteBlacklist', siteNameFilter: 'SiteNameFilter',
        maxPagesPerCategory: 'MaxPagesPerCategory', maxItemsPerRun: 'MaxItemsPerRun',
        requestDelayMs: 'RequestDelayMs', syncIntervalHours: 'SyncIntervalHours',
        incrementalHours: 'IncrementalHours', maxDeletePerRun: 'MaxDeletePerRun',
        posterMaxMb: 'PosterMaxMb', streamMode: 'StreamMode', redirectHosts: 'RedirectHosts',
        verifyMaxEpisodes: 'VerifyMaxEpisodes', detailBatchSize: 'DetailBatchSize',
        concurrency: 'Concurrency', timeoutSeconds: 'TimeoutSeconds', userAgent: 'UserAgent'
    };
    var CHECK_MAP = {
        useProxy: 'UseProxy', cleanupOrphans: 'CleanupOrphans',
        enableScheduledSync: 'EnableScheduledSync', excludeCloudDrive: 'ExcludeCloudDrive',
        incrementalSync: 'IncrementalSync', writeNfo: 'WriteNfo', downloadPoster: 'DownloadPoster',
        verifyPlayUrl: 'VerifyPlayUrl', resolveIndirectUrl: 'ResolveIndirectUrl',
        allowPrivateHosts: 'AllowPrivateHosts'
    };

    function fill(cfg) {
        if (!cfg) return 0;
        var n = 0;
        Object.keys(TEXT_MAP).forEach(function (id) {
            var e = el(id); if (!e) return;
            var v = cfg[TEXT_MAP[id]];
            e.value = (v === undefined || v === null) ? '' : v;
            n++;
        });
        Object.keys(CHECK_MAP).forEach(function (id) {
            var e = el(id); if (!e) return;
            e.checked = !!cfg[CHECK_MAP[id]];
            n++;
        });
        return n;
    }

    function collect(cfg) {
        function v(id) { var e = el(id); return e ? e.value : ''; }
        function num(id, d) { var x = parseInt(v(id), 10); return isNaN(x) ? d : x; }
        function chk(id) { var e = el(id); return !!(e && e.checked); }
        Object.keys(TEXT_MAP).forEach(function (id) {
            var key = TEXT_MAP[id], e = el(id);
            if (!e) return;
            if (e.type === 'number') cfg[key] = num(id, 0);
            else if (id === 'streamMode') cfg[key] = v(id) || 'proxy';
            else cfg[key] = v(id).trim();
        });
        Object.keys(CHECK_MAP).forEach(function (id) {
            if (el(id)) cfg[CHECK_MAP[id]] = chk(id);
        });
        return cfg;
    }

    // ① 先走公开接口把值填进去 —— 不依赖 ApiClient，页面至少不空
    function loadPublic() {
        setText('tvboxStatus', '正在读取配置…');
        fetch('/TvBox/ConfigPublic?_=' + Date.now(), { cache: 'no-store' })
            .then(function (r) { return r.json(); })
            .then(function (cfg) {
                window.__tvboxCfg = cfg;
                var n = fill(cfg);
                setText('tvboxStatus', '已读取配置（' + n + ' 项）');
                withApiClient(function (a) {
                    // 有 ApiClient 才能拿到共享令牌 → 状态/站点/启停同步才可用
                    a.getPluginConfiguration(PLUGIN_ID).then(function (real) {
                        TOKEN = (real && real.HubToken) || '';
                        if (real) { fill(real); window.__tvboxCfg = real; }
                        refreshStatus();
                    }).catch(function () { refreshStatus(); });
                }, function () {
                    setText('tvboxStatus', '已读取配置（' + n + ' 项）\n只读模式：没拿到 Emby 的 ApiClient，'
                        + '保存和启停同步请到 hub 界面（/app/tvboxhub）操作。');
                });
            })
            .catch(function (e) {
                setText('tvboxStatus', '读取配置失败: ' + (e && e.message ? e.message : e));
            });
    }

    function url(path) {
        var u = '/TvBox/' + path;
        if (!TOKEN) return u;
        return u + (u.indexOf('?') >= 0 ? '&' : '?') + 'token=' + encodeURIComponent(TOKEN);
    }

    function refreshStatus() {
        if (!TOKEN) return;                 // 只读模式下不打需要令牌的接口
        fetch(url('Status'), { cache: 'no-store' }).then(function (r) { return r.json(); }).then(function (s) {
            var lines = ['运行中: ' + (s.Running ? '是' : '否') + (s.Stage ? '  [' + s.Stage + ']' : '')];
            if (s.Running) {
                lines.push('进度: ' + s.SiteIndex + '/' + s.SiteTotal + '  当前: ' + s.CurrentSite);
                lines.push('已写: ' + s.ItemsWritten + ' 条目 / ' + s.EpisodesWritten + ' 分集，错误 ' + s.Errors);
            }
            if (s.LastError) lines.push('最后错误: ' + s.LastError);
            lines.push('输出目录: ' + (s.OutputRoot || ''));
            if (s.LastSyncUtc) lines.push('上次同步: ' + s.LastSyncUtc + ' UTC');
            if (s.LastSyncSummary) lines.push('上次结果: ' + s.LastSyncSummary);
            setText('tvboxStatus', lines.join('\n'));
        }).catch(function () { });
    }

    function save() {
        var a = apiClient();
        if (!a) {
            setText('tvboxStatus', '保存需要 Emby 的 ApiClient，当前拿不到。请到 hub 界面（/app/tvboxhub）修改后保存。');
            return false;
        }
        setText('tvboxStatus', '正在保存…');
        a.getPluginConfiguration(PLUGIN_ID).then(function (cfg) {
            cfg = collect(cfg);
            return a.updatePluginConfiguration(PLUGIN_ID, cfg);
        }).then(function () {
            setText('tvboxStatus', '已保存。');
            loadPublic();
        }).catch(function (e) {
            setText('tvboxStatus', '保存失败: ' + (e && e.message ? e.message : e));
        });
        return false;
    }

    function loadSources() {
        if (!TOKEN) { setText('tvboxSources', '查看站点需要共享令牌（只读模式下不可用，请用 hub 界面）'); return; }
        setText('tvboxSources', '读取中…');
        fetch(url('Sources'), { cache: 'no-store' }).then(function (r) { return r.json(); }).then(function (r) {
            var html = '<div>共 ' + r.Total + ' 个站点，其中 <strong>' + r.SupportedCount + '</strong> 个可直接同步</div>';
            if (r.Warnings && r.Warnings.length) html += '<div style="color:#e8a33d">警告: ' + r.Warnings.join('; ') + '</div>';
            html += '<table style="width:100%;border-collapse:collapse;margin-top:.5em">' +
                '<tr style="text-align:left"><th>状态</th><th>名称</th><th>key</th><th>type</th><th>接口</th></tr>';
            (r.Sites || []).forEach(function (s) {
                html += '<tr style="border-top:1px solid rgba(128,128,128,.25)">' +
                    '<td>' + (s.Supported ? '✅' : '⛔') + '</td><td>' + (s.Name || '') + '</td>' +
                    '<td><code>' + (s.Key || '') + '</code></td><td>' + s.Type + '</td>' +
                    '<td style="max-width:340px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + (s.Api || '') + '</td></tr>';
            });
            var box = el('tvboxSources');
            if (box) box.innerHTML = html + '</table>';
        }).catch(function (e) { setText('tvboxSources', '失败: ' + (e && e.message ? e.message : e)); });
    }

    function postAction(path, label) {
        if (!TOKEN) { setText('tvboxStatus', '启停同步需要共享令牌（只读模式下不可用，请用 hub 界面）'); return; }
        setText('tvboxStatus', label + '…');
        fetch(url(path), { method: 'POST' }).then(function (r) { return r.json(); }).then(function (r) {
            setText('tvboxStatus', (r && (r.Message || r.Msg)) || '已请求');
            setTimeout(refreshStatus, 1200);
        }).catch(function (e) { setText('tvboxStatus', '失败: ' + (e && e.message ? e.message : e)); });
    }

    function wired() {
        var f = el('tvboxForm');
        if (f && !f.__tvboxWired) { f.__tvboxWired = true; f.addEventListener('submit', function (e) { if (e) e.preventDefault(); save(); }); }
        var buttons = { btnSources: loadSources, btnStatus: refreshStatus,
                        btnSync: function () { postAction('Sync', '正在启动同步'); },
                        btnStop: function () { postAction('Stop', '正在停止'); } };
        Object.keys(buttons).forEach(function (id) {
            var b = el(id);
            if (b && !b.__tvboxWired) { b.__tvboxWired = true; b.addEventListener('click', buttons[id]); }
        });
    }

    function withApiClient(ok, fail) {
        var tries = 0;
        (function wait() {
            var a = apiClient();
            if (a) { ok(a); return; }
            if (tries++ < 30) { setTimeout(wait, 300); return; }
            fail();
        })();
    }

    function start() {
        var tries = 0;
        (function wait() {
            if (!el('configUrl')) {
                if (tries++ < 40) { setTimeout(wait, 100); return; }
                setText('tvboxStatus', '配置页控件没找到，页面结构异常');
                return;
            }
            wired();
            loadPublic();
        })();
        document.addEventListener('pageshow', function () { if (el('configUrl')) { wired(); loadPublic(); } });
        document.addEventListener('pagebeforeshow', function () { if (el('configUrl')) { wired(); loadPublic(); } });
    }

    start();
})();
