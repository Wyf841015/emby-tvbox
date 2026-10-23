// Emby 4.10 的插件配置页"控制器"。
//
// 为什么必须有这个文件：viewmanager.js 在页面显示后会执行
//     isBack = getResumeOptions(newView.controller, ...)
// 而 getResumeOptions 直接读 instance.lifeCycleAbortSignal。
// 页面没有 data-controller 时 newView.controller 是 undefined，于是抛出
//     Cannot read properties of undefined (reading 'lifeCycleAbortSignal')
// ——点开插件设置就报错，页面根本进不去。
//
// 混入规则（viewmanager.addMethodsToPluginControllerFactory）：只有原型上
// 不同时存在 onResume 和 onPause，Emby 才会把 BaseView 混进来，从而提供
// lifeCycleAbortSignal / currentResumeAbortController。所以这里故意不写它们。
define([], function () {
    'use strict';

    // 真正的配置逻辑在插件路由 /TvBox/ConfigBoot。
    // 用它而不是把逻辑写在这里，是因为这段 HTML 会被 Emby 以 innerHTML 插入，
    // 内联 <script> 不会执行；控制器是由 Emby 用 require 加载的，一定会执行。
    function boot() {
        if (window.__tvboxBooted || window.__tvboxBooting) return;
        window.__tvboxBooting = true;
        var s = window.document.createElement('script');
        // 相对路径：Emby 可能挂在网关子路径下（/emby/web/...），绝对路径会 404
        s.src = '../TvBox/ConfigBoot?_=' + Date.now();
        s.onerror = function () {
            window.__tvboxBooting = false;
            var d = window.document.getElementById('tvboxBootDiag');
            if (d) d.textContent = '（引导脚本加载失败，请刷新页面）';
        };
        window.document.head.appendChild(s);
    }

    function TvBoxConfigController(view, params) {
        this.view = view;
        this.params = params || {};
        try { boot(); } catch (e) {
            if (window.console) console.error('[tvbox] 配置页引导失败', e);
        }
    }

    return TvBoxConfigController;
});
