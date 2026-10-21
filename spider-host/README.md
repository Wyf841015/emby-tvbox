# spider-host — 在纯 JVM 上跑 TVBox 的 catvod spider

验证「能不能不重写、直接让 TVBox 的 spider 跑起来」的实验舱。

**结论：能跑。** 53% 的 spider 在无 Android 运行时的纯 JVM 里能正常返回真实数据。

---

## 为什么需要这个

TVBox 配置里 70% 以上的源是 `type: 3` + `api: csp_XXX`，对应一个全局 `spider` 包。
拆开看它不是 JAR：

```
$ file lmw.jar
Android package (APK), with MANIFEST.MF and classes.dex

内部只有 2 个文件：
  META-INF/MANIFEST.MF     146 B
  classes.dex          5,478,380 B     ← Android Dalvik 字节码，JVM 读不了
```

里面装了 **2,660 个真实站点实现**（`com.github.catvod.spider.*`），外加 3,229 个
`com.github.catvod.*` 类。

**但没有基类**：`com.github.catvod.crawler.Spider` 不在 dex 里——它由 TVBox 宿主 App 提供。
`com.github.catvod.net.OkHttp` / `utils.Json` 同样不在（0 次引用）。

这反而是好消息：宿主要提供的东西我们自己补，补多少完全可控。

## 做法

```
classes.dex ──dex2jar──▶ lmw-classes.jar（4011 个 .class）
                              │
自己补的 25 个 stub ───────────┤  com.github.catvod.crawler.Spider（基类）
   android.* 桩 ──────────────┤  android.content.Context / util.Log / text.TextUtils ...
                              ▼
                       SpiderRunner（反射调用）
                              │
              homeContent / categoryContent / detailContent
```

依赖（纯 JVM 侧，来自 Maven 镜像）：
`org.json`、`gson`、`okhttp`、`okio`、`kotlin-stdlib`

## 准备

```bash
# 1) 从 TVBox 配置里找 spider 地址（多仓里每个子仓顶层的 spider 字段）
#    形如 https://.../lmw.jar;md5;62cd1dd7
curl -L -o lmw.jar 'https://.../lmw.jar'

# 2) 取 dex
unzip -o lmw.jar classes.dex

# 3) dex→jar
curl -L -o dex-tools.zip https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip
unzip dex-tools.zip
bash dex-tools-v2.4/d2j-dex2jar.sh -f -o lmw-classes.jar classes.dex

# 4) 依赖（aliyun 镜像，几秒下完）
mvn_base=https://maven.aliyun.com/repository/public
curl -o lib/json.jar           $mvn_base/org/json/json/20240303/json-20240303.jar
curl -o lib/gson.jar           $mvn_base/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar
curl -o lib/okhttp.jar         $mvn_base/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar
curl -o lib/okio.jar           $mvn_base/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar
curl -o lib/kotlin-stdlib.jar  $mvn_base/org/jetbrains/kotlin/kotlin-stdlib/1.9.10/kotlin-stdlib-1.9.10.jar
```

## 编译 & 单测

```bash
./build.sh

export SPIDER_EXT='{"site":["https://www.hjzhencai.top"]}'
java -Xverify:none -cp "out:$JAR/lmw-classes.jar:$JAR/lib/*" \
     SpiderRunner com.github.catvod.spider.HJZhenCai home
```

`-Xverify:none` **必须加**：dex2jar 对少数类生成的字节码过不了 JVM 字节码校验，
会抛 `VerifyError: Expecting a stackmap frame at branch target`。

## 批量探测可用率

```bash
python3 probe_spiders.py <子仓配置URL> 30
```

自动：取每个 `csp_XXX` 站点的 `ext` → 映射到 `com.github.catvod.spider.XXX` →
跑 `homeContent` → 解析返回 JSON，统计 `class`/`list` 是否非空。

---

## 实测结果（🤓网络🤓 子仓，前 30 个 csp_ 站点）

**16 / 30 可用（53%）**

```
✅ Config     class=7    ✅ PanShare   class=1    ✅ 4KZhinan  class=6
✅ HJZhenCai  class=6    ✅ Libvio     class=5    ✅ HDMoli    class=3
✅ Sou4K      class=3    ✅ SeedHub    class=3    ✅ Juying    class=5
✅ Wn4k       class=5    ✅ GuanYing   class=4    ✅ Yidong4K  class=3
✅ RenRen     class=4    ✅ ShuangXing class=8    ✅ Hxq       class=5
```

真实返回样例（`HJZhenCai`，10,391 字符）：

```json
{"class":[{"type_name":"花卷高帧","type_id":"22"},
          {"type_name":"花卷电影","type_id":"1"},
          {"type_name":"花卷剧集","type_id":"2"} ...],
 "filters":{"1":[{"key":"class","name":"剧情","value":[...]}]}}
```

列表也已经能拿到：`Wn4k` 144 条、`Juying` 24 条。

## 已知缺口

**1. `detailContent` 还没打通（拿不到播放地址）**

- 失败的 12/19 都继承 `com.github.catvod.spider.Pan`（网盘类），
  `detailContent` 需要**网盘账号**（夸克/115/迅雷等）才能把分享链接解析成直链，
  没账号就返回 `{"list":[]}` —— 这是设计如此，不是我们的 bug。
- 继承 `Spider` 的直连类里，`categoryContent` 大多返回空列表，怀疑是调用约定
  （`extend` 需要带筛选值 / `pg` 格式 / 缺 headers），还没逐个排查。

**2. 失败原因分类**

| 原因 | 例子 | 处理 |
|---|---|---|
| `NullPointerException: String.startsWith` | `Wogg`、`PanWebShare` | 宿主 `Utils` 类缺失，需按开源实现补 |
| `android.app.Application.getFilesDir` 空指针 | `Douban` | Context 桩不完整 |
| 无返回（`None`） | `GuiGui`、`Mipansou`、`JuheSo` | 待查，可能是超时或静默异常 |
| `ClassNotFoundException: android.app.ActivityThread` | `Config` | 非致命，子线程里的无害异常 |

**3. 需要补的宿主类**

dex 里没有、但 spider 会调用的（按开源 CatVodSpider 实现补）：
`com.github.catvod.utils.Utils`、`com.github.catvod.net.OkHttp`、`com.github.catvod.crawler.SpiderDebug` 等。

## 下一步

1. 补齐宿主工具类，把 53% 往上推
2. 打通 `detailContent`：先找**非 Pan、纯采集站**的 spider 验证
3. 包装成 HTTP 服务，暴露 `ac=list / ac=videolist / ac=detail`，
   Emby 插件当普通 CMS 采集站对接（插件端零改动）
