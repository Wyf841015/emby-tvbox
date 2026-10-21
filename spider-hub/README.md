# spider-hub — 把 TVBox spider 变成标准采集站

TVBox 里 70%+ 的源是 `type:3` + `api:csp_XXX`，需要一个全局 spider 包（其实是 APK/dex）。
本服务把这些 spider 加载起来，**对每个站点暴露一套标准苹果CMS（MacCMS v10）接口**，
于是任何认 MacCMS 的客户端都能直接用 —— 包括本仓库的 Emby 插件。

```
Emby 插件 ──MacCMS──▶ spider-hub ──反射──▶ catvod spider（跑在 JVM 上）──▶ 源站
```

## 特性

- **Web 配置界面**：浏览器打开 `http://<host>:8098/`，选源、看状态、逐个/批量探测
- **每站一个采集地址**：`/v/<key>/api.php/provide/vod/`
- 支持 `ac=list` / `ac=videolist&t=&pg=` / `ac=detail&ids=` / `wd=关键词`
- 配置持久化（`hub.json`），支持多仓（自动取第一个可用子仓）
- 单站失败不影响其他站，错误记录在 `/api/sites` 里
- 纯 JDK 实现（`com.sun.net.httpserver`），无 Web 框架依赖

## 快速开始

```bash
# 1) 准备依赖（阿里云镜像，几秒）
mkdir -p lib spiders
m=https://maven.aliyun.com/repository/public
curl -o lib/json.jar          $m/org/json/json/20240303/json-20240303.jar
curl -o lib/gson.jar          $m/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar
curl -o lib/okhttp.jar        $m/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar
curl -o lib/okio.jar          $m/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar
curl -o lib/kotlin-stdlib.jar $m/org/jetbrains/kotlin/kotlin-stdlib/1.9.10/kotlin-stdlib-1.9.10.jar

# 2) 把 TVBox 配置里顶层 spider 字段指向的包转成 jar（见 ../spider-host/README.md）
cp /path/lmw-classes.jar spiders/

# 3) 编译 & 启动
./build.sh
PORT=8098 ./run.sh
```

浏览器打开 `http://<host>:8098/`，填 TVBox 配置地址 → 保存并加载 → 批量探测。

## Web 界面

| 区域 | 作用 |
|---|---|
| 顶部卡片 | spider 站点数、已验证可用、失败/未测、运行时长 |
| 配置 | TVBox 配置地址（http 或本机绝对路径）、保存并加载、重新加载 |
| 站点 | 每行显示 key/class/采集地址/状态；按钮「首页 / 分类 / 采集」直接看返回 |
| 批量探测 | 遍历全部站点跑 homeContent，统计可用率 |
| 结果 | JSON 查看器（含原始返回） |

## HTTP 接口

| 路径 | 说明 |
|---|---|
| `GET /` | Web 配置界面 |
| `GET /api/status` | 运行状态 + 统计 |
| `GET /api/sites` | 站点列表（含每站健康状态） |
| `GET /api/home?key=` | 取分类 + 首页列表 |
| `GET /api/category?key=&tid=&pg=` | 取某分类某页 |
| `GET /api/detail?key=&id=` | 取详情（含播放地址） |
| `GET /api/search?key=&wd=` | 搜索 |
| `POST /api/save` | `{"configUrl":"..."}` 保存配置 |
| `POST /api/reload` | 重新加载配置 |
| `GET /v/<key>/api.php/provide/vod/?...` | **MacCMS 采集接口** |

## 接到 Emby 插件

在插件设置页填「spider-hub 中转地址」（例如 `http://192.168.1.10:8098`），
插件会自动把配置里所有 `type:3 + csp_XXX` 站点改写成
`<hub>/v/<key>/api.php/provide/vod/`，然后按普通采集站同步。

**实测效果**：某个多仓配置里可同步站点从 8 个（纯 CMS）提升到 **94 个**（含 spider）。

## 实现要点

1. **必须 `-Xverify:none`**
   dex2jar 对少数类生成的字节码过不了 JVM 校验，会抛
   `VerifyError: Expecting a stackmap frame at branch target`。`run.sh` 里已带。

2. **基类由我们提供**
   dex 里没有 `com.github.catvod.crawler.Spider`，它在 `../spider-host/src` 里实现。
   spider 类用独立 `URLClassLoader` 加载，父加载器是系统 classpath，这样基类和
   android stub 都能解析到。

3. **同 key 单实例**
   spider 内部常有缓存/登录态，每次新建会重复初始化，所以池化。

4. **带超时调用**
   单次 spider 调用 45~60 秒超时，超时记录到该站点并返回错误，不拖垮整个服务。

## 当前能力边界（实测 75 个 spider）

| 能力 | 结果 |
|---|---|
| 加载 + 实例化 | ✅ 全部可加载 |
| `homeContent`（分类） | ✅ 大部分可用 |
| `categoryContent`（列表） | ✅ 36/75 能拿到条目 |
| `detailContent`（播放地址） | ⚠️ 少数可用 |

`detailContent` 大面积失败的主因：75 个里 **12/19 抽查都继承 `Pan`（网盘类）**，
它们要把夸克/115/迅雷的分享链接解析成直链，**没有网盘账号就返回空列表**。
这是设计如此，不是 bug。

已知可用样例：`急救教学` 站点 —— 12 个条目全部拿到 `vod.youlai.cn` 的 mp4 地址，
经 Emby 插件代理后 ffprobe 正常识别 `h264 + aac`。

## 目录

```
spider-hub/
├── build.sh           编译（含 ../spider-host/src 的基类和 android stub）
├── run.sh             启动（已带 -Xverify:none）
├── src/hub/Hub.java          HTTP 服务 + MacCMS 适配 + Web UI 路由
├── src/hub/SpiderPool.java   站点表、spider 实例池、内置 JSON 解析
├── web/index.html     Web 配置界面（自包含，无外部依赖）
├── lib/               运行时依赖
├── spiders/           spider jar（dex2jar 产物）
└── hub.json           运行配置（自动生成）
```
