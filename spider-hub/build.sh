#!/usr/bin/env bash
# 编译 spider-hub（含 Android stub 和 Spider 基类，来自 ../spider-host/src）。
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/vol1/dev/harmonyos/jdk/jdk-21.0.12.1+1}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
[ -x "$JAVA_HOME/bin/javac" ] || { echo "找不到 javac：$JAVA_HOME/bin/javac" >&2; exit 1; }

HOST_SRC="../spider-host/src"
[ -d "$HOST_SRC" ] || { echo "缺少 $HOST_SRC（Spider 基类和 android stub）" >&2; exit 1; }

rm -rf out && mkdir -p out
CP_LIB=""
for j in ../fpk-src/ui/lib/*.jar; do
  [ -f "$j" ] && CP_LIB="$CP_LIB:$j"
done

javac -encoding UTF-8 -d out -cp ".${CP_LIB}" \
  $(find "$HOST_SRC" src -name '*.java')
echo "编译完成: $(find out -name '*.class' | wc -l) 个 class"

# ── 用 Android 版 org.json 覆盖 lib/json.jar 的严格版 ──
# lib/json.jar 是 org.json 官方新版，getString() 遇到数字直接抛
#   JSONException: JSONObject["tag_id"] is not a string (class java.lang.Integer : 3)
# Android 自带的 org.json 是宽松的（JSON.toString 会把数字/布尔转成字符串），
# 大量 spider（好看短剧 / 金牌影视 / 一起 …）依赖这个行为。
# hub.jar 在 classpath 最前面（-cp hub.jar:lib/*），把宽松版打进 hub.jar 就一定生效。
VENDOR_JSON="$(cd .. && pwd)/spider-host/vendor/android-json.jar"
if [ -f "$VENDOR_JSON" ]; then
  ( cd out && jar xf "$VENDOR_JSON" && rm -rf META-INF )
  echo "已合并 Android 版 org.json（宽松 getString）"
else
  echo "⚠️ 缺少 $VENDOR_JSON，继续用 lib/json.jar 的严格版" >&2
fi

# ── 打 hub.jar 并同步到 fpk-src/ui ──
# 运行时的 classpath 是「hub.jar 在最前 + lib/* 兜底」，所以宽松版 org.json 必须进 hub.jar。
( cd out && "$JAVA_HOME/bin/jar" --create --file ../hub.jar . )
mkdir -p ../fpk-src/ui
cp -f hub.jar ../fpk-src/ui/hub.jar
echo "已生成 hub.jar: $(stat -c%s hub.jar) bytes"
