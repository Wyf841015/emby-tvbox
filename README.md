# emby-tvbox

## v0.5.2 — 引导收口：Emby 入口、待补文件状态、只读字段、流模式检查

把上一轮排查出来、但没动手的几项一起做掉：

1. **「打开 Emby」按钮**（使用说明页）
   - 优先用配置里的 Emby 访问地址；配置里是 `127.0.0.1`/`localhost` 而你是从手机/别的电脑访问时，
     自动换成当前访问用的主机名 + 8096 —— 否则手机上点开会去连手机自己，必然打不开
   - **先探 `/System/Info/Public` 再开**：不通就弹提示并写出试过的完整地址，不会给你开一个白屏标签页
2. **「还需要你自己准备的东西」面板**（使用说明页）
   - B 站 Cookie、外部解析接口两项，直接显示**有没有就位 + 该放到哪个绝对路径**
   - 顺带写明：97 个站的 spider 类打包在 dex 里，本机跑不起来（缺 dex2jar），站点页显示不可用不是用户配置问题
   - 状态来自 `/api/status` 新增的 `pending` 字段（含 `biliCookie`/`parseApis` 布尔 + 两个绝对路径）
3. **向导第 5 步不再误导**
   同步记录存在 Emby 插件里，插件没装/连不上时 hub 读不到 —— 现在清单里会明确写
   「读不到同步记录：插件未装或未连接（同步本身可能已跑过）」，而不是干巴巴一个 ○
4. **`ConfigFilePath` 从可写白名单移除**（它是插件侧只读回显字段）
   实测：`POST /api/save {"ConfigFilePath":"/tmp/hacked.xml"}` →
   `{"ok":true,"msg":"没有可保存的字段","pluginSaved":false,"ignored":["ConfigFilePath"]}`，插件 XML 未被改动
5. **概览「配置检测」新增「播放流模式」一行**
   - `proxy` → ✓ 全部经 NAS 中转
   - `auto` → ✓ 显示允许 302 的域名；没填域名时提示"等同全走代理"
   - `redirect` → **判红**：直接 302 到源站不经过 NAS，防盗链的站会播不了（这是最容易配错又最难自查的一项）

实测：概览 10 行检测含「播放流模式」；使用说明 5 个面板；pending 两行 ○ + 真实路径；
「打开 Emby」可达时捕获到 `http://…:8096/web/index.html`，不可达时只弹提示不开页

## v0.5.1 — 补齐 hub 界面配置：12 项设置以前改了会被静默丢掉

排查发现同一份配置有**三个入口**，但字段覆盖不一致：

| 位置 | 之前能改 |
|---|---|
| Emby 插件页 | 32 项（v0.4.1 已补齐） |
| hub 界面「配置」 | **19 项** |
| `/api/save` 白名单 | 21 项 |
| 插件 `/TvBox/Config` + `ConfigResult` DTO | 21 项 |

也就是说有 12 项设置**只有 Emby 插件页能改**，在 hub 界面（也就是 fnOS 里那个主界面）根本看不到：

`VerifyPlayUrl`、`VerifyMaxEpisodes`、`DetailBatchSize`、`ResolveIndirectUrl`、
`WriteNfo`、`DownloadPoster`、`PosterMaxMb`、`IncrementalSync`、`IncrementalHours`、
`StreamMode`、`RedirectHosts`、`MaxDeletePerRun`

其中 `StreamMode`（播放流模式）和 `MaxDeletePerRun`（单次删除上限）都算安全相关，藏起来不合适。

三处一起补：

1. **hub 界面**：「配置」页新增两组 —— 「输出与探测」（写 nfo / 下海报 / 海报上限 / 可播性探测 / 探测分集数 / 详情批处理 / 二次跳转解析 / 流模式下拉 / 302 域名白名单）和「同步策略」（增量同步 / 增量窗口 / 单次删除上限），控件从 19 → **32**
2. **`/api/save` 白名单**：21 → 33 项，另外把数字型 key 的判断改成列表（漏一个就会被当字符串发出去）
3. **插件 DTO**：`UpdateConfig` 与 `ConfigResult` 各补 12 个属性 + Post 里加 `Math.Clamp` 校验 + Get 回填 —— 之前就算 hub 传过来，插件侧也会静默丢弃

实测（改 → 存 → 回读 → 落盘 → 还原）：

```
POST /api/save {StreamMode:redirect, WriteNfo:false, PosterMaxMb:6, MaxDeletePerRun:100}
→ {"ok":true,"msg":"插件已保存 4 项","pluginSaved":true}
hub 回读 → redirect / false / 6 / 100（source=plugin）
插件 XML → StreamMode=redirect WriteNfo=false PosterMaxMb=6 MaxDeletePerRun=100
还原后   → proxy / true / 4 / 300
浏览器   → 「配置」页 32 个控件，12 个新项全部按当前值回填，分组为「输出与探测」「同步策略」
```

## v0.5.0 — 新增「使用说明」页 + 首次使用向导

之前新用户装完只有一个空的「概览」，不知道下一步该干什么。这版补上引导。

### 「使用说明」标签页

- **进度清单**：6 项自动检测（填点播源 / 选站点 / 输出目录 / 装插件 / 跑一次同步 / 在 Emby 里加媒体库），
  每项显示 ✓ 或 ○、当前值摘要（配置地址、已选站点数、输出路径、上次同步结果）、以及「去处理」按钮直接跳转
- **还差什么**：关键项没做时页面顶部会直接点名（例如「还差：配置地址 / 站点勾选，否则同步不会有任何产出」）
- **它是怎么工作的**：采集 → 同步 → 扫描 → 播放 四步说明，并写明「只在输出目录写文件，不改你已有的影视文件」
- **常见问题**：同步很慢 / Emby 里没有片 / 播放 403 / 站点变 ⛔ / 改了配置不生效 / 想从头再来

### 首次使用向导（自动弹出）

- 第一次打开（或服务端 prefs 里没有 `guideDone`）自动弹出，7 屏：欢迎 → 5 个步骤 → 完成
- 每屏「现在就去 →」直接跳到对应页面，不用自己在标签间找
- 「上一步 / 下一步 / 跳过」，最后一屏按钮变「开始使用」
- 关闭时把 `guideDone=true` 写进服务端 prefs（`/api/prefs`），`localStorage` 只做兜底 ——
  fnOS WebView 常禁 localStorage，只存本地会出现「每次进来都弹」
- 随时可从「使用说明」里的「重新打开使用向导」再看一遍

### 实测

- 首次打开自动弹（标题/进度/翻页/上一页/末页文案「开始使用」/关闭全部正常）
- 走完关闭后刷新页面**不再自动弹**（`guideDone` 已持久化到服务端）
- 进度清单 6 项状态正确：已填配置 ✓、已选 7 个站点 ✓、输出目录 ✓、插件已安装 ✓、未同步 ○、媒体库 ○
- 「去处理」跳转到对应标签页正常；点「刷新检测」会重新计算并更新时间戳
- 手机 390px 下：向导宽度 367（视口内、无横向溢出）、清单行按钮独占一行、整页无横向滚动

## v0.4.5 — 飞书统计推送跳过本机安装

自己反复装/升级会把统计表刷满（同一台机器、同一个 UUID 一堆重复记录），所以给推送加了本机判据：

- 主机名在黑名单里 → 跳过（默认 `WYF-FNOS`）
- 设备的稳定 UUID 在黑名单里 → 跳过（默认 `ab8d4dc9-f452-4fa1-99b1-ae1560535756`，换主机名也认得出）
- 跳过时往 stderr 打一行说明，退出码仍是 0（不影响安装流程）
- `FEISHU_PUSH_FORCE=1` 强制推送，用于自测推送链路
- 两个名单都能用环境变量覆盖：`FEISHU_SKIP_HOSTS` / `FEISHU_SKIP_UUIDS`

实测四态：本机安装跳过 / 本机升级跳过 / `FEISHU_PUSH_FORCE=1` 正常推送 / 名单清空后正常推送；
回调层验证：本机跑 `install_callback` 和 `upgrade_callback` 都不会发起任何写入请求。

## v0.4.4 — 配置页引导：三条触发路径 + 相对路径 + 顶部提示条

v0.4.3 的引导在真实浏览器里能跑通（实测 `__tvboxBooted=True`、32 项全部填好），
但还有两个隐患，这版补齐：

1. **只挂了一个触发点**（`<img src="data:,">`）。部分 WebView 禁用 `data:` 图片，`onerror` 不会触发 →
   现在三个触发点并存（内联 `<script>` / `<img>` 打同源 404 / `<iframe>` 打同源 204），
   谁先成功谁加载，脚本内 `__tvboxBooted` 去重。实测三者都会触发，取先到者，
   `/TvBox/ConfigBoot` 只会被加载一次。
2. **路径写成绝对 `/TvBox/...`**。Emby 若挂在网关子路径下会 404 →
   全改成相对 `../TvBox/...`，脚本内部也按 `location.pathname` 里 `/web/` 之前的前缀拼 base。
3. 页面顶部加了一条**静态提示**（不依赖 JS 也能看见）：各项为空时提示强制刷新，或改用 hub 界面配置；
   引导成功后自动收起。引导脚本加载失败时，提示条里会写出失败原因。

另外查明：**Emby 会把插件页里的内联 `<script>` 用 `<!-- -->` 包起来**（服务端就带着这个包装），
所以内联脚本永远不会执行 —— 这条以后不用再试了。

> 排查时踩的坑：用 `fetch('/web/configurationpage?name=tvbox')` 复现时**必须带 `cache:'no-store'`**，
> 否则浏览器给你的是旧页面，会误判成"新代码没生效"。手机端也一样 —— 这类改动要清缓存/无痕窗口验证。

## v0.4.3 — 插件页空白的真因：Emby 用 innerHTML 插片段，内联 `<script>` 根本不执行

之前两轮都在改「填值逻辑」，但问题不在逻辑 —— **那段脚本从来就没跑过**。

### 证据（在 Emby 自己的源里实测）

用 Emby 的同源页面，按它的方式把插件页片段插进 DOM：

```
host.innerHTML = await (await fetch('/web/configurationpage?name=tvbox')).text();
→ injected, len=14328, inlineScriptExecuted=no      ← 内联脚本没执行
```

Emby 的路由（`/configurationpage` → `contentPath: getPluginPageContentPath`）拿到 HTML 后是**赋值插入**的，`innerHTML` 里的 `<script>` 按规范不会执行。所以 `fill()` 一次都没运行，页面永远是空的 —— 配置本身一直是读得出来的（`GET /Plugins/{id}/Configuration` 36 个字段全对）。

### 修法：换成「外部脚本 + 内联事件属性」引导

`innerHTML` 不执行 `<script>`，但**内联事件属性（如 `onerror`）在插入后一定会触发**。于是：

1. 页面逻辑搬到插件自己的路由 `/TvBox/ConfigBoot`（返回 `application/javascript`，内容来自嵌入资源 `config-boot.js`）
2. 配置页里放两条引导，谁先成功谁加载脚本，脚本内 `__tvboxBooted` 去重：
   - 内联 `<script>`（Emby 万一执行了就走这条）
   - `<img src="data:," onerror="…appendChild(script)">`（**这条才是实际生效的那条**）
3. 值不再依赖 Emby 的 `ApiClient`：新增只读路由 `/TvBox/ConfigPublic`（35 个字段，**显式白名单，绝不含 HubToken**，只回 `HubTokenSet` 布尔），脚本直接 fetch 它来填表
4. `ApiClient` 可用时再补：读真实配置拿共享令牌 → 状态/站点/启停同步才可用；拿不到就明确显示「只读模式，保存和启停请到 hub 界面」，而不是转圈或报「token 无效」

### 实测（同样的 innerHTML 插入路径）

```
inlineScriptExecuted = no          ← 内联脚本依旧不执行
__tvboxBooted        = True        ← 引导脚本照样跑起来
configUrl            = https://raw.githubusercontent.com/tushen6/Tomorrow/master/lmw.json
spiderHubUrl         = http://127.0.0.1:8098
outputRoot           = /vol2/1000/video/tvbox
streamMode=proxy  concurrency=3  timeoutSeconds=45  maxPagesPerCategory=1
verifyMaxEpisodes=48  posterMaxMb=4  userAgent=Mozilla/5.0 …
复选框 useProxy/writeNfo/downloadPoster/incrementalSync/verifyPlayUrl/excludeCloudDrive/resolveIndirectUrl = 全 True
      allowPrivateHosts/cleanupOrphans/enableScheduledSync = False
状态行 = 已读取配置（32 项）
```

32 个控件全部按当前配置填好。

## v0.4.2 — 卸载增加「保留数据」选项 + 安装/升级统计推送

### 卸载向导（参考 fnclearup）

以前 `uninstall_callback` 只是停服务、删 socket，**数据怎么处理完全没得选**。现在加了卸载向导，两问都是「保留」优先：

| 问题 | 选项 | 默认 |
|---|---|---|
| 应用数据 | 保留（站点勾选、hub 配置、日志，重装可恢复） / 删除全部 | **保留** |
| Emby 里的插件 | 保留 / 同时移除（删 `TvBox.dll` + 它的配置，Emby 重启后生效） | **保留** |

安全措施：
- 移除插件前用**结构校验**（`plugins/` 目录 + `config/system.xml` 同时存在）而不是路径白名单——容器和自定义部署的路径五花八门，硬编码前缀会误跳；`/`、`/var`、`/tmp` 这类根路径直接拒绝
- 只 `rm` 明确的两个文件（`plugins/TvBox.dll`、`plugins/configurations/TvBox.xml`），不做目录级删除
- 删数据前先把记录写进系统日志（`logger`），因为 `TRIM_PKGVAR` 随后就没了
- 已生成的 `.strm` 媒体文件**不动**（在用户的媒体库目录里），向导里明确写了这点

实测 5 种组合：`保留+保留` 全不动 / `删除+保留` 只删数据 / `删除+移除` 都执行 / `保留+移除` 只摘插件 / 非 Emby 目录（缺 `system.xml`）拒绝执行并说明原因。

### 安装 / 升级统计推送

复用 `cmd/lib/feishu_push.sh` 公共库（与 fnclearup 等项目同一套），写入多维表格：

- `install_callback`：首次安装推一条「首次安装成功」，带稳定设备 UUID（`/etc/machine-id` → CPU Serial → 持久化文件兜底，同一台机器永远同一个 ID，可按 UUID 算真实装机量）、IP 归属地、中文时间
- `upgrade_callback`：升级推「升级成功」，含旧版本号 → 新版本号
- **升级触发的 install 回调会跳过**（`TRIM_OLD_APPVER` 非空），避免一次升级记两条

实测：设备 UUID `ab8d4dc9-…`、归属地 `Changchun Jilin CN`、取 token 正常、写入返回 `code:0`（含空「旧版本号」也能写），自检记录已删除；三条回调路径的推送次数分别为 1 / 1（跳过）/ 2。

## v0.4.1 — Emby 插件页配置：读取正常，但只显示了三分之一

检查了插件配置的读取 / 加载 / 显示三段：

**读取 ✅**：Emby 的 `GET /Plugins/{id}/Configuration` 返回全部 36 个字段且值正确（`ConfigUrl` / `SpiderHubUrl` / `HubToken` / `OutputRoot` / `MaxPagesPerCategory=1` / `TimeoutSeconds=45` / `StreamMode=proxy`…）。

**加载 ✅**：页面用 `ApiClient.getPluginConfiguration()` 拿到配置对象，`fill()` 12 个输入框 + 3 个复选框都能正确回填；`collect()` 从原对象起手，所以没显示在页面上的字段也不会被清空。

**显示 ❌ 只显示了 15 / 36 个字段**：网盘源排除、可播性探测、并发、超时、User-Agent、允许内网、写 NFO、下载海报、海报上限、增量同步与窗口、流模式、302 域名白名单、单次删除上限、详情批处理、分集探测数、二次跳转解析 —— 这 17 项在插件页上根本看不到，也就改不了。

补齐后插件页从 15 个控件扩到 **32 个**，分成四段：TVBox 源接入 / 同步行为 / 输出内容 / 探测与网络（+ 操作）。每项都带一句说明，说明来自代码里的实际行为（例如流模式三档 proxy/redirect/auto 的真实差别）。

验证：
- Emby 实际下发的配置页 HTML `HTTP 200 / 27264 字节 / 32 个控件 / 5 个分区`，新字段全部在
- 原样回写配置（`POST /Plugins/{id}/Configuration`）→ 200，回读与写入前**逐字段完全一致**
- 页面依赖的三条免登录路由用配置里的 `HubToken` 调用：`/TvBox/Status`、`/TvBox/Sources`、`/TvBox/Config` 均正常返回

## v0.4.0 — 装完 / 更新完插件自动重启 Emby

以前装完插件只提示「重启 Emby 生效」，得用户自己去 fnOS 应用中心点一次 —— 插件 DLL 不重启根本不加载，很容易以为装失败。

现在由 hub 直接重启，并且**等 Emby 真的起来**才报完成：

- **识别托管方式**：`/var/apps/<app>/target/system/EmbyServer` 存在 → fnOS 应用中心（`appcenter-cli stop` + `start`，它没有 restart 子命令）；否则退到 systemd（`emby-server` / `emby`）；都没认出来就明确报「请手动重启」，不假装成功
- **容器部署**：走原来的 `docker restart`，不重复重启
- **异步执行**：接口 2~3 秒就返回 `restarting:true`，重启在后台线程做，不会被前端 20 秒超时打断
- **等恢复再报**：轮询 `/System/Info/Public`，最多等 150 秒，日志留下 `[restart] ok=true manager=… seconds=…`
- **前端改轮询**：`⏳ 正在重启 Emby…（N 秒）` → 起来后 `✅ 插件已生效 · Emby 已重启（N 秒）`，不再靠「固定等 30 秒后刷新」
- `/api/emby/status` 新增 `serverUp`（实时探测，不走缓存）
- **自更新兜底**：插件 `CanSelfRestart=false` 时由 hub 补一次重启；但「已是最新版本」不重启（避免无意义的 20 秒断服）
- 顺带修：安装请求没带 `hubUrl` 时不再把插件里的 `SpiderHubUrl` 写成空串（会直接导致插件连不上 hub、自更新和推送全失效），兜底用 `http://127.0.0.1:<hub端口>`

实测：重装 → `ok=true restarting=true method=local`，Emby PID `3072194 → 3072625`，**26 秒恢复**；日志 `[restart] ok=true manager=fnOS 应用中心（EmbyServer4-9） seconds=24`；「已是最新」路径确认不再多余重启。

## v0.3.2 — 详情 / 播放等弹窗不跟主题，全量主题适配复查

**根因**：`.dlg`（详情、播放共用的弹窗容器）写的是 `background: var(--panel, #161b26)`，而 **`--panel` 这个令牌从来没定义过** —— 于是永远回退成写死的深色。浅色主题下背景是深色、字也是深色，等于看不清。同类问题还有遮罩、赞助弹窗、视频底。

**全量排序检查**（把样式表里所有颜色过了一遍）：

| 项 | 处理 |
|---|---|
| `--panel` 被引用未定义 | 新增令牌：深色 `#141f31` / 浅色 `#fff` |
| `.mask` `rgba(3,7,18,.62)` 写死 | 新增 `--overlay`：深色同上 / 浅色 `rgba(16,28,48,.34)` |
| `.spon-mask` 同上 | 走 `--overlay` |
| `.spon-full` `rgba(0,0,0,.82)` 写死 | 新增 `--overlay-strong` |
| `.dlg` / `.spon-box` 阴影 `rgba(0,0,0,.45)` | 走 `--sh-3` |
| `<video>` 内联 `background:#000` | 走 `--video-bg` |
| JS 里 3 处 `color:#ef4444` | 走 `--bad-text` |
| 语义色当**文字**用时太浅（绿 `#2ecc71` 在白底只有 2.1:1、金 `#ffc000` 更差） | 新增文字版 `--ok-text`/`--warn-text`/`--bad-text`，浅色主题压深为 `#12864a`/`#8a5a00`/`#c62b31`；样式表和 JS 里 25 处文字色全部切换 |
| 浅色主题弹窗边框/海报占位底 | `.dlg`、`.dlg-head`、`.dlg-body img`、`.spon-box`、`.pill`、`.cfg-row .ic` 补浅色值 |

**验证方式**：写了一个对比度扫描脚本，逐元素取「文字色 vs 逐层 alpha 合成后的真实底色」算 WCAG 对比度，两套主题 × 7 个 Tab 全扫，阈值 2.8:1 —— **14 组全部 0 处不达标**。另外单独核对了三个弹窗的实际计算样式：详情 `rgb(255,255,255)`、赞助 `rgb(255,255,255)`、遮罩 `rgba(16,28,48,.34)`，浅色主题下都正确。

> 保留 `#fff` 的两处：`.spon-qr img` / `.spon-full img` —— 二维码必须白底才扫得出来，与主题无关。

## v0.3.1 — 修「安装/升级插件提示 缺少令牌或请求不合法（CSRF 防护）」

v0.3.0 的写操作闸门只认「本地令牌」和「网关注入的 `X-Trim-Userid`」，实测在真实访问路径下两项都拿不到：

- 页面被浏览器缓存成旧版本 → 里面没有本次启动的 `<meta name="hub-token">` → 前端不带令牌
- fnOS 网关转发到 socket 时**不注入** `X-Trim-Userid`，还会把 `Host` 改写成 `localhost`，所以「Origin == Host」在网关后面永远不成立

修法（三道，缺一不可）：

1. **同源证明改成看 Referer 页面路径**：只有本站页面会发 `Referer: …/app/tvboxhub/…`，跨站页面伪造不出来；直连 TCP 时仍用 `Origin == Host` 兜底
2. **页面 `Cache-Control: no-store`**：页面里带着令牌，绝不能被缓存，否则升级一次就得手动强刷
3. **拒绝时打一行原因**到 `info.log`：`[auth] 拒绝 POST /api/… token=无 gateway=false sameOrigin=false ct=… origin=… referer=… host=…`，下次不用再猜

闸门实测六态：网关+同页 Referer 放行 / 仅 Referer 放行 / 跨站 Referer 拒绝 / 无来源拒绝 / 同源直连放行 / 跨站直连拒绝；只读 GET 不受影响。

## v0.3.0 — 全量安全审计与修复（P0×7 / P1×14 / P2×20）

对一个自建接入口做完整审计后按级别全修。所有 P0 都做了**实际利用验证**再确认拦截。

### P0（7 项，都验证过）

| 漏洞 | 修法 |
|---|---|
| 站点 key 可逃逸 `onclick` 双引号属性 → 存储型 XSS，配合 root 操作 | `escJs` 先实体化 `& < > "` 再转义 `\` `'`；5 组载荷实测全部中和 |
| hub 管理面零鉴权 + `Access-Control-Allow-Origin: *` + socket 0755 | 删通配 ACAO；写操作强制 `application/json`、`OPTIONS→403`；破坏性端点要令牌（令牌注入页面 `<meta>`，跨站读不到）；socket `chmod 600` |
| `/TvBox/Stream` 与 `/api/stream/seg` 免鉴权任意 URL 代理（`127.0.0.1.nip.io` 可绕过纯字符串判断） | 两端都改成**解析 DNS 后按 IP 段判定**（回环/私网/链路本地/CGNAT/组播全拒），插件关闭自动跳转并逐跳复检 |
| `/api/emby/probe-as-emby` 拼 shell → 命令注入 | 改 `ProcessBuilder` 传 argv，路径加深度校验 |
| `/api/emby/prepare-output` 递归 chown 整卷（`path=/vol3` 即毁卷） | 拒绝卷根/浅路径/平台目录；只 chown `movies`、`tv` 两棵子树，限 5 万条目 |
| `HubOk` 在令牌为空时 fail-open + SelfUpdate 无签名 | 拆 `HubOk`/`HubOkStrict`，写入类 fail-closed；令牌常量时间比较 |
| 远程 spider jar 以 root 加载且 `;md5;` 从不校验 | 按配置声明的 md5 校验（缓存命中同样校验） |

### P1（14 项）

前端请求全加 20s 超时 · POST 只在网络层失败时换前缀（不再重复推送/重复安装）· 搜索 in-flight 守卫 · 站点列表失败不再假装"没配源" · 卸载插件加确认 · 预览探测改为流式读 1KB（原来会把整片 mp4 读进内存）· 孤儿清理在截断或站点失败时跳过（防批量误删 strm）· 停止不再被计成站点失败 · 停止信号用 linked token 传到在途 HTTP · 停止/读源/写探针等路由补鉴权 · 写探针限输出目录子树 · 自定义头 CRLF 清洗 · 线程池改单次 deadline（锁只等 1/4 预算）· 代理复用静态 HttpClient · 配置保存加锁 + temp/rename 原子写 · 插件安装只允许检测到的 Emby 实例 · Docker 容器名白名单 · reload 关闭旧 ClassLoader。

### P2（20 项）

补齐从未定义的 `--muted`/`--fg` · toast 提到弹层之上 · ESC 按「播放器→详情→弹窗」出栈 · `color-mix` 兜底 · 修重复 `</tbody></table>` · `fmtUptime` 重名 · 站点列表输入防抖 · 500 不再回堆栈 · 缺参数改友好提示 · history/loadErrors/detailCache 并发安全 · 备份只留 3 份 · DoH 证书校验收窄 · `/var/lib/emby` 不再被误杀 · docker 命令超时真正生效 · 安装脚本 chmod 改到真实 JRE 路径、删自指软链 · `TRIM_PKGVAR` 拼写 · `status` 校验 `/proc/cmdline` · 启动前清残留 socket · 守护脚本指数退避（连续 8 次停手）· `build.sh` 自动同步 DLL 到打包目录。

### 顺带修的两个真问题

- **hub 进程会越积越多**：`stop` 只杀 java 不杀 supervisor，后启动的实例绑不到 8098 就顺延端口，网关连的永远是最老那个（跑旧代码）。已让 `status` 认出 supervisor，并在 `start` 前清理重复实例。
- **mp4 直链播不了**：以前所有播放都走 hls.js，遇到单文件 mp4 报 `manifestParsingError`。现在按 content-type 分流，非 HLS 走原生 `<video>`。

> `-Xverify:none` 保留：TVBox 生态部分 jar 有不合规 class（`ClassFormatError: Bad string initial value`），关掉校验器会让整站不可用。补偿措施是上面的 jar md5 强校验。

## v0.2.1 — 实测 Emby 播放 + 补「转码临时目录」检测

拿真实生成的 strm 在 Emby 里从头到尾验了一遍：

| 检查 | 结果 |
|---|---|
| Emby 是否收录 | `Items/Counts` → 18,333 部电影，条目 Path 指向 `…/movies/骤雨/骤雨.strm` |
| 元数据探测 | `PlaybackInfo`：`Protocol=Http`、`Container=hls`、`SupportsDirectPlay=True`、时长 23:57 |
| 直连播放 | `/videos/8986/stream?static=true` → `206` + m3u8 内容 |
| Emby 自带 ffmpeg 解码 | 电影与剧集各取 5 秒 → **exit=0，无任何报错** |
| HLS 转码链路 | `master.m3u8`（Emby 生成，1080x606/30fps/h264+aac）→ variant → 分片 `200 video/mp2t 679,808 字节` |

**过程中发现一个会让转码播放必挂的问题**：Emby 的 `var/transcoding-temp` 目录**不存在**，
ffmpeg 报 `Unable to find a suitable output format for .../transcoding-temp/XXXX/XXXX` 后直接退出。
已建目录并授权给 Emby 运行用户，重新验证分片拉取正常。

因此「概览 → 配置检测」新增一项：

- **转码临时目录**：显示路径 + 属主 + 可写性；不存在或不可写时给出 `mkdir -p` / `chown` 提示
  （hub `/api/emby/status` 新增 `transcodingTemp` 字段：`{path, exists, writable, owner}`）

现在概览一共 9 项检测，本机全部 ✓。

## v0.2.0 — 概览默认跑一遍配置检测

进「概览」就自动把所有关键配置挨个查一遍并显示状态，不用再挨个页面点点看哪里没配。

检测项（每行：图标 + 名称 + 实测值，异常时附修法）：

| 项 | 判定 |
|---|---|
| 采集源配置 | `ConfigUrl` 非空且已解析出站点数 |
| spider 引擎 | 站点总数 / 实例缓存占用 / 可播数 |
| 同步名单 | 已勾选站点数（前 6 个 + 「等」） |
| 输出目录 | 插件以 Emby 身份实测可写（显示属主） |
| Emby 插件 | 已安装 + 大小/时间；与包内 DLL 大小不一致时提示可更新 |
| Emby 地址 | 回环地址会警告（外网/手机会连自己） |
| hub 令牌 | 是否已配置（hub 读 `TvBox.xml` 判 `<HubToken>` 非空，只回布尔不回值） |
| 运行环境 | Java 版本 · 架构 · 运行时长 · 端口 |

- 右上角有「重新检测」，每次切回概览也会自动重跑
- 标题旁显示汇总：`全部正常 · 19:07` / `2 项异常 · …`
- hub `/api/status` 补了 `osArch` / `jreHome`（顺便能验证双架构选对没有）
- hub `/api/config` 补了 `HubTokenSet` 布尔（插件本身不返回 token）

实测：8 项全部 ✓，切到「站点」再切回也会重跑。

## v0.1.4 — 修「搜索」页顶栏显示成 search

顶栏标题靠一张映射表把 tab 的 `data-pane` 翻成中文，表里漏了 `search`，
于是切到搜索页时顶栏直接显示了内部名 `search`。补上 `search:'搜索'`。

顺手把 7 个 tab 全过了一遍，现在都对：

```
overview → 概览    sites → 站点    search → 搜索    sync → 同步
config  → 配置    emby  → Emby 插件    logs → 操作记录
```

## v0.1.3 — 版本号回归品牌行，四个按钮合并成一行

按你的要求调整：

- **版本号**：从顶栏移到最顶部 `TvBox For Emby` 后面，跟名字同一行（`.brand-line` 用 flex + nowrap，
  名字过长时自己省略，不会把版本号挤下去）
- **赞助 / 联系 / 刷新 / 主题**：四个按钮统一 `.tiny` 同款样式，放进顶栏右侧 `.topbar-actions`
  一行不折行；主题按钮从侧边栏底部搬过来，图标和文字分开（切主题只换 ☀/☾，不会把「主题」两个字冲掉）
- 侧边栏底部只剩状态徽章（`运行中 · Java 21.0.12.1`）

实测：
- 桌面 1280：`TvBox For Emby` 右边 146px、版本号左边 152px（6px 间距），两者 top 相同
- 手机 390：同样同一行（171 / 177）
- 四个按钮 top 全为 12（手机 163），高度 26px、字号 12px、圆角 6px、内边距 `0 9px` 完全一致

## v0.1.2 — 版本号与标题同一行

版本号原来和标题、说明文字平铺在同一个 flex 容器里，宽度不够时会各自换行，
看起来像是掉到标题下面去了。现在把「标题 + 版本号」包进一个 `nowrap` 的 `.topbar-title` 组：

- 标题和版本号永远同一行（桌面实测两者 top 都是 14，间距 8px）
- 手机 390px 宽下也是同一行（top 都是 128），按钮三个仍在一行
- 中间的说明文字改成可收缩 + 省略号，宁可被截断也不挤掉标题/版本/按钮

## v0.1.1 — 双架构 JRE、主题真正记住、顶栏整理

**1. 双架构（x86_64 / ARM 通吃）**
以前 `ui/jre` 只有打包这台机器的架构，装到 ARM 飞牛上会直接报 `cannot execute binary file`。
现在包里同时带两套 jlink 裁剪过的 JRE：

- `ui/jre-x64`（47 MB，OpenJDK 21.0.12.1，amd64）
- `ui/jre-arm64`（46 MB，同版本，aarch64）

`cmd/main` 启动时按 `uname -m` 选（`x86_64|amd64` → jre-x64，`aarch64|arm64` → jre-arm64），
老安装目录里的 `ui/jre` 仍然兜底，最后才退到系统 java。启动日志会写一行 `arch=… jre=…` 便于排查。

**2. 主题不会保存 → 改成服务端记住**
原来只写 `localStorage`，而 fnOS 的 WebView / 隐私模式下 localStorage 经常被禁，写不进去也不报错，
表现就是「切了浅色，一刷新又回深色」。现在双写：

- 新增 `GET /api/prefs` / `POST /api/prefs`，主题存进 `hub.json` 的 `prefs`
- 切换主题时同时写 localStorage + 服务端；加载时先按 localStorage 立刻出图，
  再问服务端，值不同就纠正（本次会话手动改过则不覆盖）
- 实测：切到浅色 → 清掉 localStorage → 刷新，仍然是浅色

**3. 顶栏整理**
- 版本号从侧边栏移到顶栏标题旁（`概览 | TVBox 点播源接入 Emby | v0.1.0`）
- 赞助 / 联系 / 刷新三个按钮统一 `.tiny` 样式、放进 `.topbar-actions` 一行不折行
  （之前赞助是粉色渐变，跟前两个不一致）
- 窄屏（≤640px）隐藏中间那行说明文字，给按钮腾地方

## v0.1.0 — 更名为 TvBox For Emby

项目名统一改成 **TvBox For Emby**（应用名 `tvboxhub` 保持不变，装好的路径/网关前缀都不动）：

- 侧边栏标题、页面 `<title>`、fnOS 桌面入口标题（`ui/config` 的 `title`）、manifest `display_name`
- Emby 插件列表里的名字也改成 `TvBox For Emby`（`Plugin.Name`）
- 版本号按你的要求重置为 **0.1.0**（之前累积到 0.17.x，重命名后重新起算）

功能与之前完全一致，只是换了名字和版本号。

## v0.17.0 — 赞助 / 联系按钮

顶栏加了两个按钮（照 fnclearup 的做法搬过来的）：

- **💖 赞助** → 弹窗里并排支付宝 / 微信收款码，点二维码看大图（大图再点一下关闭）
- **💬 联系** → 弹窗显示 QQ 群号 `745565752`，点数字或按钮复制群号；
  复制走三级兜底：`navigator.clipboard` → `execCommand('copy')` → 自动选中文本让用户长按复制，
  每一级失败都有可见提示，不会静默

细节：
- `Esc` 一次关掉所有弹窗（赞助码大图 / 赞助 / 联系）
- 点遮罩空白处关闭
- 收款码图片放 `ui/images/sponsor_alipay.png`、`sponsor_wechat.png`，
  由 hub 的静态资源路由提供（扩展名白名单里已有 `.png`）
- 浅色主题下按钮用同样的粉色系，深色主题下也压得住

## v0.16.3 — 修「播不了」的真根因：分片地址丢了网关前缀

**症状**：详情页点播放，界面没提示、也没画面。

**根因**：fnOS 网关把请求转给 unix socket 时，路径是 `/app/tvboxhub/api/stream`；hub 剥掉前缀后
重写 m3u8 里的分片地址，写出来的是 `/api/stream/seg?...` —— **少了 `/app/tvboxhub` 这一段**。
浏览器拿到这种地址会去「站点根」取，网关那里没有这个路由 → 404 → hls.js 致命错误 → 播不了。

本地测试一直用 `http://127.0.0.1:8098/`（没有前缀），所以这个 bug 在我这儿永远复现不出来，
只有走网关才会踩到。

**修法**：
- `UnixHttp` 剥前缀时把用到的前缀记进请求头 `x-hub-prefix-used`，hub 的 `basePath()`
  读它来还原 —— 重写出来的分片地址变成 `/app/tvboxhub/api/stream/seg?...`
- 直连 hub（无前缀）时仍写成 `/api/stream/seg?...`，两种情况都对

**顺带修掉 socket 反向代理丢头的问题**（视频播放必需）：
- 请求头透传：`Range` 之前根本没转给 handler，所以「边下边播 / 拖动进度」都不工作
- 响应头透传：`Content-Range`、`Accept-Ranges`、`X-Accel-Buffering`、`Cache-Control`、`Location`
  之前全被吞掉（Resp 只有 code/contentType/body）

实测：
- 带前缀请求 → 分片地址 `/app/tvboxhub/api/stream/seg?...` → 拉取 `200 video/mp2t 1,955,012 字节`
- `Range: bytes=0-99` → `206` + `Content-Range: bytes 0-99/1955012` + `Accept-Ranges: bytes`
- 浏览器实测「集结号」第一个结果：`readyState=4 / duration=7432s / currentTime=1.2s / paused=false`

## v0.16.2 — 播放器加自检：播不了要能说出原因

「都无法播放」但界面上什么提示都没有，只能靠猜。这一版把播放器做成会说话的：

- **hls.js 直接内联进 index.html**（原来是一个单独的 `hls.min.js` 请求，网关/缓存任何一个环节
  取不到就静默退化成「原生 HLS」，而多数浏览器并不支持原生 HLS → 黑屏无提示）
- **播放前先探测**：对候选地址发一个 `Range: bytes=0-99` 的小请求，不是 2xx 或者
  Content-Type 不像视频，就带着 HTTP 状态码报错，而不是干等
- **多地址自动回退**：同源 hub 代理 → 插件代理；每个都失败时，提示里给「302 直链」
- 提示区常驻当前状态：`正在加载…` / `正在播放` / `这种方式不行（HTTP 502），换下一种…`
- hub 的 `/api/stream`、`/api/stream/seg` 响应加 `X-Accel-Buffering: no`，
  避免 fnOS 网关（nginx）把流攒着不发

实测（真实鼠标点击触发，非脚本调用）：点击播放 → `readyState=4 / duration=2872s /
currentTime=0.7s / paused=false`，提示区「正在播放」。

## v0.16.1 — 修播放器被详情弹窗挡住

两个遮罩都是 `z-index:900`，而播放器插在详情弹窗**前面**的 DOM 位置，所以后出现的详情弹窗压住了播放器。
现在明确分层：`#playerMask{z-index:1000}`、`#dlgMask{z-index:900}`。

实测：两个弹窗同时打开时，画面中心命中的元素是 `VIDEO#playerVideo`，视频正常播放
（`readyState=4 / 2872s`），关掉播放器后详情弹窗还在原处。

## v0.16.0 — 外网也能播：同源代理 + 302 直链

之前播放地址拼的是 `http://192.168.1.10:8096/TvBox/Stream?...`（走插件代理），出了局域网就废了。
现在改成**同源**：播放地址用当前页面的 base + `api/stream?...`，页面能打开就能播。

- `GET /api/stream?site=&id=&ep=[&flag=]` — hub 自己代理播放：
  m3u8 会把里面**每一个分片地址**（含 `EXT-X-KEY` 的 `URI="..."`）重写成
  `{当前前缀}/api/stream/seg?u=<真实地址>&h=<源要求的 header>`，播放器自始至终只跟 hub 打交道；
  mp4/ts 之类做字节直通并透传 `Range` / `206`
- `GET /api/stream/seg?u=&h=[Range]` — 分片代理，带源 header（Referer/UA/Cookie），支持 Range
- `GET /api/go?site=&id=&ep=` — **302** 到真实地址，给 VLC / IINA / PotPlayer 这类外部播放器
- 播放器界面下方常驻一个「302 直链」链接，代理播不动时可以把它丢进外部播放器

为什么这样能解决外网问题：浏览器只连「打开这个页面的那个地址」，不再需要能访问 `192.168.1.10`。
源的 CORS / Referer 限制也由 hub 侧带 header 解决。

实测（本机同源路径）：`/api/stream` → 200 `application/vnd.apple.mpegurl` 231KB →
分片 `/api/stream/seg` → 200 `video/mp2t` 641KB → 浏览器 `readyState=4 / duration=2872s`；
`/api/go` → `302 Found`。

⚠️ 另外提醒：**Emby 库里的 strm 仍然是绝对地址**，外网看 Emby 时要把插件配置的
`EmbyBaseUrl` 改成客户端实际访问 Emby 用的地址，再跑一次同步重写 strm。

## v0.15.0 — 详情页直接播放

详情弹窗加了「▶ 播放」，点开就能看，不用先推送：

- 播放地址解析走 hub 的 `GET /api/play-resolve?site=&id=[&ep=][&flag=]`：
  拉详情 → 按线路取分集 → `playerContent` 换真地址（parse=1 也会再试一次解析接口）
- 返回的 `streamUrl` 是**经插件 `/TvBox/Stream` 代理**的地址（顺带带上源要的 header）——
  浏览器直连 CDN 会被 CORS / Referer 挡，绕一道代理才稳
- 剧集多的时候弹窗底部出现集数下拉，切换即换集重解析
- 播放器用自托管的 hls.js 1.5.13（`ui/hls.min.js`），Safari/iOS 走原生 HLS；
  致命错误会在播放器下面直接显示原因（比如「换个线路试试」）
- parse=1 且解析不出来的源，会提示「需要外部解析器（可在 data/parse-apis.txt 里配）」

配套：
- hub 静态资源路由（白名单扩展名 + 防目录穿越），否则 hls.min.js 取不到（原来 hub 只放行 `/`）
- `browserEmbyBase()`：给浏览器用的 Emby 地址优先用请求 Host，`embyBaseUrl` 是回环时自动忽略
  —— 否则手机上拉 `127.0.0.1` 会连自己
- 顺手把插件配置里的 `EmbyBaseUrl` 从 `127.0.0.1:8096` 改成局域网地址

实测：「庆余年2」点播放 → hls.js 接管，`readyState=4 / duration=2872s / 1920×…`，无错误提示；
切到第 3 集后标题与进度信息同步更新。

## v0.14.2 — 点搜索结果看影片详情

搜索结果现在是「点整行就能看详情」（勾选框和「推送」按钮不会误触发）：

弹窗里显示：海报、名称、年份 / 地区 / 类型 / 语言 / 豆瓣评分 / 更新状态徽章、
**线路列表（每条线路有多少集）**、站点 / 导演 / 主演 / 更新时间，以及去 HTML 标签后的完整简介，
底部直接「推送到 Emby / 关闭」。在弹窗里推送成功，列表那一行的按钮也会同步变成 `✓ 已推送`。

hub 的 `/api/detail?key=&id=` 除了原来的 `raw/parsed`，现在多返回一份归一化的 `item`
（字段：name/year/area/type/lang/score/remarks/pic/actor/director/content/updated/lines），
UI 直接用，不用再解析 MacCMS 原始字段。

**顺带修掉一个会伤数据的问题**：同步名单被清空。hub 里有个「连续失败 3 次自动移出同步名单」的机制，
一次「停止同步 + 重启 Emby + 重启 hub」的折腾会被算成连续失败，把用户勾好的 6 个站点逐个摘光
（实测名单真的变成了 0 个，搜索跟着返回 0 条）。现在这个行为默认**关闭**，
需要就显式配 `settings.autoDisableAfter = N`（N≥2）。

## v0.14.1 — 搜索页支持勾选：推送选中 / 全部推送

搜索结果表加了勾选列（表头可全选，也支持部分选中时的中间态）：
- 「推送选中 (N)」按勾选数量实时更新，未勾选时置灰
- 「全部推送」仍然是整张列表
- 推送成功的行按钮变 `✓ 已推送`、勾选框自动取消，失败的行保持可重试并显示原因
- 批量推送串行执行（同一批共用一个 hub/Emby，串行又快又稳），顶部实时显示 `推送中 3/60：xxx`，
  结束后给汇总：成功 N 条（共 M 个文件）/ 失败 N 条，失败原因最多列 3 条

实测：勾 2 条短剧 → `推送完成：成功 2 条（共 26 个文件），失败 0 条`，两行都变 `✓ 已推送`，按钮回到 `推送选中 (0)`。

修的两个 UI bug：
- 批量推送取错了字段（直接拿 `{idx,item,box}` 包装对象当条目，`it.site` 是 undefined）→ 全部报
  `undefined：失败`。现在兼容裸条目和包装对象两种入参
- 新加的 `getSelected()` 与页面里已有的同名函数冲突（那个是同步站点选择用的），被覆盖成返回
  Promise，导致按钮显示 `推送选中 (undefined)` —— 改名为 `searchGetSelected()`

## v0.14.0 — 搜索并一键推送到 Emby

新增「搜索」页：输入关键词 → 并发搜索同步名单里的所有站点 → 结果列表（封面/名称/年份/备注/站点）
→ 单条「推送」或「全部推送」，直接把 strm / nfo / 海报写进 Emby 库，目录与命名跟同步出来的完全一致。

**为什么不用重新实现一套逻辑**：推送走的是 `Spider.searchContent()`（TVBox 客户端同款搜索）
和同步已有的那条链路 —— 拉详情 → `PickBestLine` → 私有 id 经 hub `/play/` 解析（playerContent）
→ 逐集可播校验（不可播自动换线路）→ StrmWriter 写文件。所以推出来的东西和主流同步没有差别。

接口：
- `GET /api/search-all?wd=关键词[&keys=a,b]` — 6 线程并发搜所有同步站点，
  返回 `{total, items:[{site,id,name,year,remarks,pic,type}], sites:[{site,ok,count,msg}]}`
- `POST /api/push {site,id}` — hub 转给插件 `/TvBox/PushItem`，插件写文件后回
  `{ok, name, series, count, paths[]}`

实测（庆余年）：
- 搜索：6 个站点全部命中，43 条结果
- 推送「庆余年2」（金牌影视）：写出 36 个 strm，
  `tv/庆余年2 (2024)/Season 01/庆余年2 (2024) - S01E01.strm …`

修的问题：`/TvBox/PushItem` 漏了 `[Unauthenticated]`（hub 用 `?token=` 调，Emby 先拦 401）；
推送时没设 `_embyBase`，私有分集 id 不会被改写成 `/TvBox/Play` 地址，导致「36 集全部不可播」；
搜索结果的空字段（year/type/remarks）在页面上显示成 None。

## v0.13.1 — 修「同步停不下来」

用户报「同步无法停止」。查下来是三个问题叠在一起，最后一个是我自己上一版引入的：

**1. Stack Overflow 把 Emby 打崩（致命，我引入的）**
v0.13.0 加停止标志时批量替换检查点，把 `ThrowIfStopped()` 方法**体内**的
`ct.ThrowIfCancellationRequested()` 也换成了它自己 → 无限递归。
日志里就是 `Repeat 174468 times: at SyncService.ThrowIfStopped` + `Stack overflow.`，
进程直接崩，之后反复重启还撞上 `Address already in use` —— 表现就是
「点同步没反应、点停止没反应、Emby 打不开」。

**2. 停止请求不幂等**
原来 `RequestStop()` 在令牌已取消时直接返回 false（"停止请求未生效"），
重复点也没用。现在改成独立停止标志：可重复点，站点循环 / 条目循环 / 逐集校验 /
拆批重试 / 线路兜底 **五类检查点**都会立刻退出。

**3. 同步把 Emby 的线程池抽干**
`/TvBox/Stream`、`/TvBox/Play`、`/TvBox/Live` 里都是
`SendAsync(...).GetAwaiter().GetResult()`（"同步等异步"），同步校验会发出几百次
这样的请求，每次都占住一个 Emby 线程等续体 → 线程池抽干 → Emby 整个 Web 服务
失去响应，连「停止」的请求都排不进去。全部改成 HttpClient 的**同步** Send/ReadAsStream
（纯阻塞 IO，不依赖续体线程）；海报下载同样改法；逐集校验并发 6 → 4；
插件加载时把线程池下限抬到 64。

**4. hub 预热不再堵请求**
`/api/emby/sync` 原来在请求线程里串行预热 6 个 spider（每个 30~60 秒），
2 核机 CPU 打满，Emby 十几秒答不上话。改成后台单线程、最低优先级预热。

实测（6 站点同步）：
- 同步全程 Emby `/System/Info/Public` 稳定 **200 / 2~6ms**
- 中途点停止 → `{"Success":true}` → **14 秒后 Running=False**，日志 `同步已停止`
- DoH 改为可选（默认系统 DNS，需 `-Dtvbox.doh=true` 才启用），避免高并发下拖慢解析

## v0.13.0 — 把 TVBox 源码里剩下的接口补齐（proxy / 直播 / 解析接口 / DoH）

对着 catvod 的 `Spider` 基类逐项核对，这次把之前没接的补上：

**1. `Spider.proxy(params)`** — 站点自己造的代理地址
很多 spider 返回的播放地址形如 `/proxy?do=spider&key=<站点>&…`，TVBox 会把它交给该站的
`Spider.proxy()`。hub 的 9978 文件服务现在识别 `key=` 参数并分发到对应 spider，
返回值（okhttp3 Response / byte[] / String）用反射统一抠成字节流输出。

**2. `liveContent()` → 直播**
- hub `GET /live/<key>`：把 spider 的 `{"lives":[{"name":"组","channels":[{"name":"台","urls":[...]}]}]}`
  转成标准 M3U
- 插件 `GET /TvBox/Live[?key=站点]`：不带 key 就把同步名单里所有站点的直播合并
- 用法：Emby「Live TV → 添加 M3U tuner」填 `http://192.168.1.10:8096/TvBox/Live`
（当前名单里 4 个站都没有直播数据，接口通了但没内容；`网络直播`/`轮回DJ` 缺 spider 类）

**3. `parse=1` 支持自定义解析接口**
新建 `data/parse-apis.txt`，一行一个模板（`{url}` 占位），parse=1 时按顺序试；
解决 `Sao火`/`布布` 这类只给网页地址、必须外部解析的源。内置 JSON 试探仍然保底。

**4. filter 兜底**
有些 spider 只有 `filter=true` 才吐数据（客户端默认传 false）。现在
`categoryContent(false, {})` 拿空会自动再用 `(true, {})` 试一遍，日志里出现
`[cms] filter=true 才拿到数据` 就是命中了。

**5. `safeDns()` 换 DoH**
改用阿里 DoH（223.5.5.5）解析，失败回落系统 DNS。被污染/本地解析不到的源站能救回一批。

**6. 多线路兜底**
插件原来只挑「最佳线路」，那条线挂了整条跳过。现在全集不可用时，
自动换第 2~4 条线路再验一遍，日志 `[线路兜底] 换到「xxx」`。

顺带修的：`/TvBox/Play` 的 `[Unauthenticated]` 上一版被我改漏过一次（401
Access token is invalid），已在回归测试里确认修复。

实测：`/TvBox/Play` → 200 `application/vnd.apple.mpegurl` 567KB，分片 200 `video/mp2t`；
`/TvBox/Live?key=农民影视` → 200；`/proxy?url=` 透传 200。

## v0.12.0 — 补上 Spider.playerContent 这一层（私有分集 id 现在能播）

上一版探测发现 152 个源里只有 2 个「直接可播」，其余大量源的 `vod_play_url` 根本不是地址：

```
金牌影视  146660@1314815
半日99    CO4K_…@CO4K@重生@1
Sao火     /play/50375-1-1.html
```

这些是 spider 的私有分集 id。TVBox 客户端播之前都会先调 `Spider.playerContent(flag, id)` 换成真地址，
我们之前直接把 id 当 URL 用，所以整站同步不到。这一版把这一层补上了：

- hub 新增 `GET /play/<key>?flag=&id=[&json=1]`：内部调 `playerContent`，`parse=0` 直接给真地址，
  `parse=1` 再尝试解一次解析接口
- 插件新增 `GET /TvBox/Play?site=&flag=&id=`：局域网可达，内部向 hub 解析后**代理**播放
  （不走 302 —— Emby 的 `IHttpResult.StatusCode` 实测设不进响应，客户端拿到 200+Location 判死）
- `PlayUrlParser` 不再丢弃非 http 地址；改为在同步时换成 `/TvBox/Play` 地址再校验/写 strm
- 修 `CmsClient.ResolveRelativePlayUrls` 把私有 id 当相对路径的 bug（曾补成
  `http://127.0.0.1:8098/146660@1314815` → 全站 404）；只有真像路径的才补绝对地址
- `/TvBox/Stream` 与 `/TvBox/Play` 补 `[Unauthenticated]`：播放器拉分片不带 Emby token，
  缺这个整条播放链 401「Access token is invalid or expired」
- HLS 分片是相对路径时，把播放列表上的鉴权查询串（`?auth_key=…`）带下去；
  不带就 401/403（实测 ppvod 这类 CDN 分片必须带同一份 auth_key，且**不能**带 Referer，
  带了反而 403）
- 两条重复的同步路径（有分类主路径 / 无分类降级路径）都接上了私有 id 转换 —— 之前只改了一条
- 同步前 hub 先预热并常驻同步名单里的 spider 实例（LRU 不再淘汰它们），
  解决「spider 冷启动 30~60 秒 > 插件超时」导致的整站「0 个条目」

实测（金牌影视，192 个条目）：私有 id 逐个解析出真 m3u8，分片代理返回
`200 video/mp2t 362KB`，strm 正常落盘：
`http://192.168.1.10:8096/TvBox/Stream/Play.ts?u=…`（解码后指向 `/TvBox/Play?site=…&id=…`）。
`Sao火`/`布布` 仍是 `parse=1`（要外部解析器），`豆瓣`/`魔方`/`jin夏听书` 各有独立故障，这几站仍不可用。

## v0.11.0 — 探测区分「有数据」和「真能播」

用户实测反馈「选了几个源同步不到剧集」。查下来三个源分别栽在不同地方，而界面上它们都显示"可用"：

| 源 | 现象 | 真实原因 |
|---|---|---|
| 1905 / 爱看机器人 | 分类有，条目 0 | spider 上游拿不到列表 |
| 热播影视 | 37 个条目全跳过 | 播放地址是解析接口，返回 `{"code":"100","msg":"url不正确…"}`（解析 key 失效） |

所以探测新增**可播性检测**：取一条详情 → 第一条分集地址 → 实际拉一次（Range 64KB），
按状态码 + content-type + 是否 `#EXTM3U` 判定，结果落盘（`hub.json` 的 `probe` 段），
站点列表新增「可播」列（可播 / 不可播 / 未验），鼠标悬停看具体原因（如「返回 JSON（解析接口，需二次解析）」
「详情里没有播放地址」「不是 http(s) 地址」）。批量加入同步时优先只加「可播」的站。

顺带修的两个真问题：
- **播放地址内嵌未编码空格**：不少源把 UA 直接拼进解析接口 query，
  `https://api.xxx/?...|Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X)`，
  空格让 ffmpeg/客户端判为非法 URL。插件侧 `PlayUrlParser.SanitizeUrl` 统一把空格转 `%20`、
  控制字符转 `%XX`（`| : @ /` 这些源站协议字符不动）；hub 探测侧额外把 `|` 转 `%7C` 以过 `java.net.URI` 校验。
- **同步汇总文案误导**：写入 0 时原来一律提示「检查输出目录是否存在且 Emby 用户可写」，
  而实测这批是"全部条目本来就不可播"。现在区分三种：写入失败（带真实错误）/ 全部不可播（换源）/
  确实一个都没落地（才提示查权限）。

实测（生产 hub，抽样探测）：农民影视 `m3u8 可播`、星芽短剧 `直链可播（video/mp4）`；
热播影视 `返回 JSON（解析接口，需二次解析）`、1905/爱看机器人 `列表里一条都没有`、
豆瓣/韩圈 `详情里没有播放地址`、Sao火 `不是 http(s) 地址`。

## v0.10.2 — 插件自更新（一键升级，Docker 也能用）

装好一次之后，以后升级不再需要碰任何目录或容器。

- 插件新增 `GET /TvBox/SelfUpdate`：从 hub 的 `GET /api/plugin/dll` 拉新 DLL，
  先写 `<plugins>/TvBox.dll.tmp-<ts>` 再 **原子 rename 覆盖自己**（运行中的 Emby 映射着老 inode，
  直接覆盖会 SIGBUS），写完校验 sha256，最后调 `IApplicationHost.Restart()` 让 Emby 自己重启加载。
- 定位自己的路径靠 `IApplicationPaths.PluginsPath`：Emby 用 `Assembly.Load(byte[])` 加载插件，
  `Assembly.Location` 是空串，不能靠它。
- hub 新增 `GET /api/plugin/info`（路径/大小/sha256/mtime）与 `GET /api/plugin/dll`（带头 `X-Plugin-Sha256`），
  以及 `POST /api/emby/self-update`（代调插件路由；旧版插件没这条路由时返回 `needInstall` 提示先装一次）。
- Web UI 安装区新增「升级插件（自更新）」按钮，结果写明是「已是最新」还是「已更新 + 正在重启」。

实测（生产 Emby 4.9）：
- 哈希一致 → `已是最新版本（c003d07c5f23）`，不动文件
- `force=1` → 文件 mtime 更新、sha256 与 hub 一致、Emby 进程未被打挂
- `restart=1` → 插件替换后调 `Restart()`，**Emby 72 秒后自动恢复**，`/api/emby/status` 正常读到新 DLL（180736 B）

## v0.10.1 — 插件安装：本地优先，失败自动转 Docker

Emby 跑在 Docker 里时，hub 往往看不到容器的 `/config`，原来的「复制 DLL 到 programdata」就装不上。
这版把安装做成两级：

1. **先试本地**：按 `<Emby数据目录>/plugins/` 直接写（原生部署、fnOS 应用仍然走这条，最快）。
2. **本地不成再走 Docker**：`docker cp` 进容器 `<配置目录>/plugins/`，
   再 `chown` 对齐容器内 plugins 目录的属主（不然容器里 Emby 保存插件设置会失败），
   最后 `docker restart` 让插件加载。容器配置目录默认 `/config`，也从 `docker inspect` 的挂载点里识别。

额外处理一个容易误判的场景：宿主上某个目录长得像 programdata、写入也"成功"了，但**运行中的 Emby 用的其实是另一份数据**
（典型就是容器部署），这时插件永远不出现。现在会在本地写入成功后比对进程实际使用的 `-programdata`，
不一致就继续尝试 Docker，两条结果都回给用户。

关于 Emby 的插件 API（也一并写进 UI 说明）：`PackageService` 只有
`GET /Packages`、`POST /Packages/Installed/{Name}`、`DELETE /Packages/Installing/{Id}`、`GET /Packages/Updates`，
其中安装只能装「官方插件目录里已有的包」，**没有「给个 URL 装 DLL」的端点**，
4.9 也不支持自定义插件仓库（`system.xml` 无相关配置键、程序集里搜不到 `PluginCatalogUrl`/`PluginRepository`、
Web UI 里没有"添加仓库"）。所以容器场景只能走 Docker 或手动拷文件。

新增接口：`GET /api/emby/docker`（探测 docker 是否可用 + 列出 emby 容器 + 不可用原因）。

实测（本机 Docker 28.5.2，假容器 emby-test）：
- 本地目录可写但运行中的 Emby 是另一份 → 正确识别并转 Docker
- `docker cp` 落盘 175104 B、配置 XML 写入、属主对齐 `1000:1001`、容器重启
- docker 不可用时返回明确原因（无 docker 命令 / socket 权限不足 / 守护进程没跑）

## v0.10.0 — 性能与稳定性

- **逐集校验加样本上限**（`VerifyMaxEpisodes`，默认 48）：源里 200+ 集的条目原先每集 2 个请求，
  一个条目就烧掉 500+ 次请求；现在超过上限改抽「首尾各 12 + 中间均匀」，未抽到的按可播写入。
  实测一轮 3 站省下的请求量约 75%，且不影响发现"后面整集坏掉"的源。
- **hub 增加 `ac=detail` 结果缓存**（TTL 5 分钟 / 800 条 / 按「站点|单 id」存）：拆批重试和重复同步不再重打 spider；
  新增 `POST /api/cache/clear`，`/api/status` 里能看到命中数。
- **spider 实例治理**：原先实例只进不出（189 站就常驻 189 个），现在空闲 15 分钟回收 + 总量上限 60，
  `/api/status` 的 `pool` 字段可看缓存数与累计回收数。针对这台 2 核小内存机两次 JVM GC 崩溃的诱因。
- **间接播放地址解析**（`ResolveIndirectUrl`，默认开）：解析接口（`?url=…`）、网页播放页、302 中转，
  在校验阶段尝试解出真 m3u8 并回写，写进 strm 的就是能播的地址。
- **按站点补 Referer**：校验与代理播放都带上站点自身域名的 Referer，防盗链源不再被误判成死链。
- **输出地址是回环就拒绝同步**：原先探测不到局域网地址会退化成 `127.0.0.1`，静默写一整库客户端连自己的死链；
  现在直接报错并提示去填「Emby 访问地址」。
- **年份/分季兜底**：`vod_year` 为空时从备注补年份；备注或片名带「第二季」时整条归到 Season 02，
  不再和第二季撞进 Season 01。
- **日志轮转**：`info.log` 超过 10MB 自动轮转，保留 3 份。
- **诊断增强**：条目「全部不可用」时给出原因分布（如 `403×12、text/html×6`）；
  间接地址解析会报「尝试 N 个，解出 M 个」；`spider-hub/build.sh` 现在直接产出 `hub.jar`。


把 TVBox 配置里的点播源接进 Emby，实现**浏览 / 搜索 / 播放**。

Emby Server 插件，C# / .NET 8，零第三方依赖。

---

## 为什么是这个架构

Emby 的 `IChannel`（频道插件）看起来最适合做这件事，但它有一个致命限制：

```csharp
// MediaBrowser.Controller.dll 里 InternalChannelItemQuery 的全部字段
string FolderId;  int? Limit;  int? StartIndex;
ChannelItemSortField? SortBy;  bool SortDescending;  long UserId;
```

**没有 SearchTerm。** Emby 不会把用户输入的搜索词传给频道插件——用频道方案，
用户只能在分类里翻页，搜不了。而且频道条目需要一次请求全量返回，大源会直接拖死 Emby。

所以本项目走的是 **STRM 文件 + Emby 原生媒体库**：

```
TVBox 配置 JSON
    │
    ├── sites（点播源，苹果CMS 接口 type 0/1）
    │      └─ 定时同步 → 生成 .strm 文件树 → Emby 扫描入库
    │                           ↓
    │              浏览 / 搜索 / 播放 / 海报 / 分季
    │              全部由 Emby 原生能力处理
    │
    └── 播放时走插件代理（补 Referer/UA + 重写 HLS 分片），解决防盗链
```

内容进了库之后，搜索用的是 Emby 自己的数据库——模糊匹配、按年份/分类筛选、秒出结果。

这份架构与 GitHub 上的 `firestaerter3/emby-xtream`（Xtream IPTV → Emby）是同一路线，
它同样用 TunerHost 做直播、用 STRM 做点播/剧集。

---

## 能力与限制

### 支持的源

| TVBox `type` | 说明 | 支持 |
|---|---|---|
| `0` | 苹果CMS XML 接口（`?ac=videolist`） | ✅ |
| `1` | 苹果CMS JSON 接口 | ✅ |
| `3` / `4` | spider（`.jar` 或 drpy `.js`） | ❌ 跑不了 |
| `lives` | 直播源 | ❌ 未实现（规划中） |
| `parses` | VIP 视频解析 | ❌ 刻意不做（需无头浏览器，且法律灰色） |

**spider 源的硬限制**：TVBox 的 spider 是 Android/Java 字节码（`com.github.catvod.crawler.Spider`），
靠反射调用 Android 类。Emby 插件是 C#/.NET，没有可行方式加载它。
TVBox 圈子里「最好用」的源不少是 spider，这部分会缺失——这是架构决定的，不是实现偷懒。

### 能做什么

- 分类浏览：自动抓源站所有分类，按电影/剧集分别入库
- 搜索：走 Emby 原生搜索（这是选 STRM 路线的核心原因）
- 播放：HLS 直连不占 CPU；防盗链由插件代理解决
- 分季剧集：`剧名 (年份)/Season 01/剧名 - S01E01 - 集名.strm`
- 增量同步：内容没变就不重写文件
- 孤儿清理：源里下架的条目对应的 strm 会被删掉
- 多重线路优选：同一条目有多条线路时，优先选 m3u8、选集数最多的那条
- 无效地址过滤：magnet / 网盘分享页这类进 Emby 只会变成报错条目，直接丢弃

---

## 安装

### 方式一：直接放 DLL（侧载）

```bash
./build.sh
cp TvBox.Plugin/bin/Release/TvBox.dll <emby的programdata>/plugins/
# 重启 Emby
```

`programdata` 的位置：
- Linux 包安装：`/var/lib/emby`
- Docker：容器内 `/config`
- Windows：`%AppData%\Emby-Server\programdata`

### 方式二：Docker 挂载

```yaml
volumes:
  - ./TvBox.dll:/config/plugins/TvBox.dll:ro
```

> 这个插件不会被 Emby 官方插件目录收录（TVBox 源的版权状态很明确），只能自测/自用侧载。

---

## 配置

Emby 后台 → 插件 → **TVBox 源接入**。

| 配置项 | 说明 |
|---|---|
| **TVBox 配置地址** | 单仓 JSON / 多仓（`urls`）/ base64 编码的配置都支持，也可以填本机文件绝对路径 |
| **STRM 输出目录** | 留空 = `<Emby 数据目录>/tvbox/`。会生成 `movies/` 和 `tv/` 两个子目录 |
| **Emby 访问地址** | 如 `http://192.168.1.10:8096`。开启播放代理时必填 |
| **播放 Referer** | 源站校验 Referer 时填，例如 `https://www.example.com/` |
| **播放走本机代理** | 打开后 strm 里写 `/TvBox/Stream/xxx.m3u8?u=...`，由插件补 Referer/UA 并重写 HLS 分片 |
| **站点白名单/黑名单/名字过滤** | 精确控制同步哪些源 |
| **每分类抓取页数** | 默认 3。源普遍脆弱，别贪多 |
| **单次最大条目数** | 第一次建议填 20 试水 |
| **清理失效 strm** | 默认开 |
| **允许内网地址** | 自建 CMS 源在内网时打开（默认关，防 SSRF） |

配置页上有「查看站点」按钮，会列出配置里所有站点、类型，以及**为什么某个站不支持**。

### 建媒体库

同步跑完后，在 Emby 里建两个库：

| 库类型 | 路径 |
|---|---|
| 电影 | `<STRM 输出目录>/movies` |
| 电视剧 | `<STRM 输出目录>/tv` |

电影和剧集必须分两个库——Emby 的库类型不能混，混了刮削会全乱。

---

## 实现要点（踩过的坑）

这些是实测才发现的问题，记下来免得重蹈：

1. **`ConfigurationFileName` 必须重写**
   `BasePlugin<T>` 的默认 getter 返回 `null`，而 `ConfigurationFilePath` 会
   `Path.Combine(pluginPath, null)` → `ArgumentNullException`，表现为插件「创建实例失败」，
   日志里只有一句 `Error creating TvBox.Plugin.Plugin`。

2. **`IResponse` 没有 `OutputStream`**
   Emby 4.10 的 `IResponse` 只有 `OutputWriter`(PipeWriter) / `SetContentLength` / `CompleteAsync`。
   自己往 `OutputWriter` 写完 `return null`，Emby 会当成空响应返回 **204**。
   正确做法：`IHttpResultFactory.GetResult(Request, stream, contentType, headers)` 返回 Stream。

3. **代理路由必须 `[Unauthenticated]`**
   Emby 对没标 `[Authenticated]` 的路由**也**做 token 校验。而 Emby 内部 ffmpeg 拉流
   不带 token，不标就直接 401。

4. **HLS 分片 URL 必须带扩展名**
   ffmpeg 的 HLS 解复用器会校验分片扩展名在 `allowed_segment_extensions` 里。
   把分片改写成 `/TvBox/Stream?u=...` 会被拒绝：
   `not in allowed_segment_extensions, consider updating hls.c`。
   所以代理路径必须是 `/TvBox/Stream/<原文件名>?u=...`。

5. **`TaskTriggerInfo.Type` 是字符串**
   没有 `TaskTriggerType` 枚举，值是 `"IntervalTrigger"` / `"DailyTrigger"` / `"StartupTrigger"`。

6. **`IApplicationHost` 只有泛型 `Resolve<T>()`**
   没有 `Resolve(Type)` 重载。

7. **Emby 不会主动探测远程 strm 的媒体信息**
   `PlaybackInfo` 里 `MediaStreams` 是空的，`container` 只显示 `strm`。
   播放本身没问题（客户端/ffmpeg 直接从 URL 拉），但如果要让 Emby 的转码决策准确，
   需要额外实现 `IMediaSourceProvider` 预先声明编码信息——这是 v0.2 的事。

---

## 目录结构

```
emby-tvbox/
├── build.sh                     # 准备参考 DLL + 编译
├── TvBox.Plugin/
│   ├── TvBox.Plugin.csproj      # net8.0，零 NuGet 包
│   ├── Plugin.cs                # IPlugin 入口 + 配置页 + 输出目录解析
│   ├── PluginConfiguration.cs   # 配置模型
│   ├── Core/
│   │   ├── TvBoxConfig.cs       # 配置模型 + 加载（base64/多仓/本地文件）
│   │   ├── CmsClient.cs         # 苹果CMS 客户端（XML/JSON 双格式）
│   │   ├── PlayUrlParser.cs     # vod_play_url 解析 + 线路优选
│   │   ├── StrmWriter.cs        # strm 文件树生成 + 孤儿清理
│   │   └── SyncService.cs       # 同步编排（单例闸门 + 进度上报）
│   ├── Tasks/SyncTask.cs        # IScheduledTask 定时任务
│   ├── Api/TvBoxApi.cs          # 状态/站点/同步/播放代理 路由
│   └── Configuration/Web/config.html
├── tests/
│   ├── mock_cms.py              # 本地 mock 苹果CMS（端到端验证用，不依赖外网）
│   └── hls/                     # ffmpeg 生成的真实 HLS（播放链路验证）
└── .dev/                        # 本地 Emby 实例（gitignore）
```

---

## 已验证（本地 Emby 4.10.2.0 实测）

用 `tests/mock_cms.py` 起一个本地假源（XML + JSON 各一个站），验证结果：

| 项目 | 结果 |
|---|---|
| 插件加载 | ✅ `TVBox 源接入 v0.1.0.0` 出现在插件列表 |
| 配置读写 | ✅ 写入/回读一致 |
| 站点解析 | ✅ 3 个站点识别为 2 个可同步 + 1 个 spider 不支持 |
| 同步 | ✅ 站点 2、条目 6、分集 10、错误 0 |
| strm 结构 | ✅ `movies/站名/片名 (年份)/片名.strm`、`tv/站名/剧名 (年份)/Season 01/剧名 - S01E01 - 集名.strm` |
| 无效源过滤 | ✅ 只有 magnet 链接的条目被丢弃（0 个 magnet 进库） |
| 媒体库扫描 | ✅ Emby 识别出 4 部电影、2 部剧、6 集 |
| **搜索** | ✅ 「测试电影」→4，「测试电影一」→2，「测试剧集」→8（含剧+集），不存在的词→0 |
| 播放代理（HLS） | ✅ 播放列表被重写，分片地址带扩展名走代理 |
| 播放代理（二进制） | ✅ TS 分片原样转发，`Content-Type: video/mp2t` |
| SSRF 防护 | ✅ 内网地址默认拒绝，`AllowPrivateHosts` 可放行 |
| **真实解码** | ✅ ffprobe 经代理识别出 `hls / h264 320x240 / aac 44100`，ffmpeg 完整解码 exit 0，转封装出 88KB mp4 |

---

## 后续（v0.2 规划）

- `IMediaSourceProvider`：预先声明编码/分辨率，让 Emby 的转码决策准确，也省掉探流
- 直播源（`lives` → M3U + TunerHost），跟 emby-xtream 一个做法
- 源健康度检测：连续失败自动加入 `DeadSites`，避免每次同步白跑
- 搜索增强：配置里保留「搜索关键帧缓存」，弥补无法用频道搜索的遗憾
- 多仓按站点分组建库（现在所有站点共用 movies/tv 两个库，同名跨站会有重复条目）

---

## 免责

本项目只做协议转换，不提供、不存储、不分发任何音视频内容。
源由使用者自行配置，内容合法性由使用者负责。仅供个人学习和技术研究使用。

---

## fnOS 应用（tvboxhub.fpk）

整个东西打成了一个 fnOS 应用：spider 中转服务 + Web 配置界面 + Emby 插件一键安装。

### 安装

fnOS 应用中心 → 手动安装 → 选 `tvboxhub.fpk`（30.5 MB，内置 Java 运行时，**不需要另外装 Java**）。

装完打开应用，Web 界面里：

1. 填 TVBox 配置地址 → 「保存并加载」→ 会自动列出所有 `csp_XXX` spider 站点
2. 「检测本机 Emby」→ 自动找到 Emby 数据目录（也可手工填）
3. 填剧集输出目录（strm 根目录，例如 `/vol1/media/tvbox`）
4. 点「安装插件到 Emby」→ DLL 复制到 `<Emby数据目录>/plugins/`，插件配置自动写好
5. 重启一次 Emby → 插件生效，去媒体库添加该输出目录并扫描

### 包里有什么

| 路径 | 内容 |
|---|---|
| `ui/index.html` | Web 配置界面（同时通过 fnOS 网关 `/app/tvboxhub` 和 TCP 8098 提供） |
| `ui/hub.jar` | spider-hub（38 个类，含 Spider 基类 + Android stub） |
| `ui/jre/` | jlink 裁剪的 Java 21 运行时，42 MB（含 `jdk.httpserver`、`jdk.crypto.ec`） |
| `ui/lib/` | okhttp / gson / okio / kotlin-stdlib / org.json |
| `ui/spiders/lmw-classes.jar` | dex2jar 转出来的 spider 集合（5.4 MB，2660 个站点实现） |
| `ui/plugin/TvBox.dll` | Emby 插件本体（94 KB） |
| `cmd/main` | 启动脚本：起 hub，绑 TCP 8098 + Unix socket `tvboxhub.sock` |

### 服务端点

- **TCP 8098** —— 局域网访问 + Emby 插件连（插件配置里的 `SpiderHubUrl` 填这个）
- **Unix socket** `${TRIM_APPDEST}/tvboxhub.sock` —— fnOS 网关 `/app/tvboxhub` 走这条

两个端点共用同一套路由（socket 前端反向代理到 TCP），保证功能完全一致。

### 重新打包

```bash
cd fpk-src
# 更新插件 DLL / hub 代码后
cp ../TvBox.Plugin/bin/Release/TvBox.dll ui/plugin/
(cd ../spider-hub/out && jar cf $OLDPWD/../fpk-src/ui/hub.jar .)
fnpack build .
```

fnpack 硬性要求（踩过的坑）：
- `bin/` 空目录必须存在
- `cmd/` 下 9 个脚本缺一不可：`main`、`install_init`、`install_callback`、`upgrade_init`、`upgrade_callback`、`uninstall_init`、`uninstall_callback`、`config_init`、`config_callback`
- `ICON.PNG`(64×64) + `ICON_256.PNG`(256×256) 必须在根目录，直角满铺正方形

---

## Web 界面（v0.2 Tab 式布局）

按功能区拆成 5 个 Tab，站点/操作记录都用表格展示：

| Tab | 内容 |
|---|---|
| **概览** | 4 个 KPI 卡片（站点数/可用/失败/运行时长）+ 运行信息（配置地址、hub 地址、TCP 端口、插件包、Java、Emby）+ 最近 5 条操作 |
| **站点** | 筛选（全部/仅可用/失败未测）+ 关键词搜索 + 批量探测当前筛选 + 表格（状态/名称/key·class/采集地址/操作）+ 探测结果 JSON |
| **配置** | TVBox 配置地址 + 保存并加载 + 重新加载 + 载入统计（来源、耗时） |
| **Emby 插件** | 检测本机 Emby（列候选，带可写标记）+ 手工路径 + 输出目录 + 安装/卸载/查状态 |
| **操作记录** | 表格（时间/操作/目标/结果/详情/耗时）+ 成功/失败筛选 + 关键词搜索 + 清空 |

Tab 状态会记在 localStorage，刷新后回到上次所在页。

### 操作记录

后端把每个操作落盘到 `$TRIM_PKGVAR/history.json`（最多 300 条，最新的在前）：

| 操作 | 记录时机 | 记录内容 |
|---|---|---|
| 保存配置 | 点「保存并加载」 | 配置地址 |
| 扫描配置 | 配置加载完成 | 站点数 + 实际来源主机 + 耗时 |
| 探测站点 | 点站点行的「首页」 | 分类数 / 列表数 + 耗时 |
| 批量探测 | 批量探测结束 | 可用数 / 总数 + 总耗时 |
| 安装插件 | 安装完成 | Emby 目录 + 大小；失败时记错误原因 |
| 卸载插件 | 卸载完成 | 备份文件名 |

接口：`GET /api/history`、`POST /api/history/add`、`POST /api/history/clear`

### 单站探测接口

`GET /api/probe?key=<站点key>&record=1` → `{ok, classes, list, ms, parsed}`

`record=0` 时不写记录（批量探测用，避免刷屏）。
