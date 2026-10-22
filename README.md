# emby-tvbox

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
