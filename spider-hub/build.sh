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
javac -encoding UTF-8 -d out \
  $(find "$HOST_SRC" src -name '*.java')
echo "编译完成: $(find out -name '*.class' | wc -l) 个 class"
