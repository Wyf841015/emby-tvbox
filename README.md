# emby-tvbox

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
