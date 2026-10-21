#!/usr/bin/env python3
"""
批量验证：把 TVBox 配置里 type=3 / api=csp_XXX 的站点，
在纯 JVM 里逐个跑 homeContent，统计真实可用率。

用法: python3 probe_spiders.py <子仓配置URL或本地文件> [最多测几个]

对每个站点：
  取 ext（原样传给 spider.init）
  映射 csp_AiKan → com.github.catvod.spider.AiKan
  跑 SpiderRunner，抓返回 JSON 里 class/list 是否非空
"""
import json
import os
import re
import subprocess
import sys

JAVA = "/vol1/dev/harmonyos/jdk/jdk-21.0.12.1+1/bin/java"
HOST = "/vol3/1000/hermes/emby-tvbox/.dev/spider-host"
JAR = "/vol3/1000/hermes/emby-tvbox/.dev/jar"
CP = ":".join([
    f"{HOST}/out",
    f"{JAR}/lmw-classes.jar",
    f"{JAR}/lib/json.jar",
    f"{JAR}/lib/gson.jar",
    f"{JAR}/lib/okhttp.jar",
    f"{JAR}/lib/okio.jar",
    f"{JAR}/lib/kotlin-stdlib.jar",
])


def load_cfg(src):
    if src.startswith("http"):
        out = subprocess.run(["curl", "-sS", "-L", "--http1.1", "--max-time", "40", src],
                             capture_output=True, text=True, timeout=60).stdout
    else:
        out = open(src, encoding="utf-8").read()
    return json.loads(out)


def ext_to_str(ext):
    """TVBox 的 ext 可能是字符串、对象、数字，spider 期望统一成字符串。"""
    if ext is None:
        return ""
    if isinstance(ext, str):
        return ext
    return json.dumps(ext, ensure_ascii=False)


def run_one(cls, ext, timeout=70):
    try:
        os.remove("/tmp/spider_out.json")
    except OSError:
        pass
    env = dict(os.environ, SPIDER_EXT=ext)
    try:
        r = subprocess.run(
            [JAVA, "-Xverify:none", "-cp", CP, "SpiderRunner", cls, "home"],
            capture_output=True, text=True, timeout=timeout, env=env, cwd=HOST,
        )
    except subprocess.TimeoutExpired:
        return {"ok": False, "reason": "超时"}
    out = (r.stdout or "") + (r.stderr or "")
    if "❌ 类加载失败" in out:
        m = re.search(r"❌ 类加载失败: (.+)", out)
        return {"ok": False, "reason": "类加载失败: " + (m.group(1)[:80] if m else "?")}
    if "❌ 实例化失败" in out:
        return {"ok": False, "reason": "实例化失败"}
    if "❌ 调用失败" in out:
        m = re.search(r"❌ 调用失败: (.+)", out)
        return {"ok": False, "reason": "调用失败: " + (m.group(1)[:80] if m else "?")}
    m = re.search(r"✅ 返回 (\d+) 字符", out)
    if not m:
        return {"ok": False, "reason": "无输出"}
    try:
        body = open("/tmp/spider_out.json", encoding="utf-8").read()
    except Exception:
        return {"ok": False, "reason": "没写出结果文件"}
    try:
        j = json.loads(body)
    except Exception:
        # 有的 spider 返回的不是合法 JSON，也当失败但保留片段
        return {"ok": False, "reason": "返回非 JSON", "raw": body[:120]}
    nclass = len(j.get("class") or [])
    nlist = len(j.get("list") or [])
    return {
        "ok": nclass > 0 or nlist > 0,
        "classes": nclass,
        "list": nlist,
        "raw": body[:160],
    }


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else ""
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    cfg = load_cfg(src)
    sites = cfg.get("sites") or []

    target = []
    for s in sites:
        api = str(s.get("api") or "")
        if s.get("type") != 3 or not api.startswith("csp_"):
            continue
        cls = "com.github.catvod.spider." + api[4:]
        target.append((s.get("key"), s.get("name"), cls, ext_to_str(s.get("ext"))))

    print(f"配置里 csp_ 站点 {len(target)} 个，先测前 {min(limit, len(target))} 个\n")
    ok, fail = [], []
    for key, name, cls, ext in target[:limit]:
        short = cls.rsplit(".", 1)[-1]
        res = run_one(cls, ext)
        if res.get("ok"):
            ok.append((short, res))
            print(f"  ✅ {short:<22} class={res.get('classes')} list={res.get('list')}")
        else:
            fail.append((short, res))
            print(f"  ❌ {short:<22} {res.get('reason')}")

    print(f"\n结果: 可用 {len(ok)} / {len(ok) + len(fail)}")
    if ok:
        print("\n可用站点详情:")
        for short, res in ok[:6]:
            print(f"  {short}: {res.get('raw')}")
    json.dump({"ok": [x[0] for x in ok], "fail": {x[0]: x[1].get('reason') for x in fail}},
              open("/tmp/spider_probe_result.json", "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
