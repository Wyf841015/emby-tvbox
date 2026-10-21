#!/usr/bin/env bash
# 启动 spider-hub。
#
#   ./run.sh                 # 默认端口 8098
#   PORT=9000 ./run.sh
#
# 需要的目录结构：
#   spider-hub/
#   ├── out/            编译产物（先跑 ./build.sh）
#   ├── lib/            json/gson/okhttp/okio/kotlin-stdlib（见 README）
#   ├── spiders/        从 TVBox 配置里下载的 spider dex 转成的 jar
#   ├── web/index.html  Web 配置界面
#   └── hub.json        运行配置（首次启动自动生成）
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/vol1/dev/harmonyos/jdk/jdk-21.0.12.1+1}"
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"
[ -x "$JAVA" ] || JAVA=java

PORT="${PORT:-8098}"
SPIDER_JAR="${SPIDER_JAR:-}"
JAR_DIR="${JAR_DIR:-lib}"

# 自动找 spiders/ 下的第一个 jar（一般是 lmw-classes.jar 之类）
if [ -z "$SPIDER_JAR" ] && [ -d spiders ]; then
  SPIDER_JAR="$(find spiders -maxdepth 1 -name '*.jar' | head -1)"
fi

[ -d out ] || { echo "还没编译，先跑 ./build.sh" >&2; exit 1; }

CP="out:$JAR_DIR/*"
# spider jar 单独放在 CDP_SPIDER 位置，由 Hub 用 URLClassLoader 加载
echo "启动 spider-hub  port=$PORT  spider-jar=${SPIDER_JAR:-（未配置）}"

exec "$JAVA" \
  -Xverify:none \
  -Xmx512m \
  -Dhub.dir="$(pwd)" \
  -cp "$CP" \
  hub.Hub \
  --port "$PORT" \
  --config hub.json \
  ${SPIDER_JAR:+--spider-jar "$SPIDER_JAR"} \
  --jar-dir "$JAR_DIR"
