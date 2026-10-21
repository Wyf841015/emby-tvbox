#!/usr/bin/env bash
# 编译 spider-host：把 TVBox 的 catvod spider 跑在纯 JVM 上（无 Android 运行时）。
#
# 前置：
#   JRE/JDK 11+（本机用 /vol1/dev/harmonyos/jdk/jdk-21.0.12.1+1）
#   已把 classes.dex 转成 lmw-classes.jar（见 README 的「准备」一节）
#   lib/ 下有 json / gson / okhttp / okio / kotlin-stdlib
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/vol1/dev/harmonyos/jdk/jdk-21.0.12.1+1}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

[ -x "$JAVA_HOME/bin/javac" ] || { echo "找不到 javac：$JAVA_HOME/bin/javac" >&2; exit 1; }

rm -rf out && mkdir -p out
# okhttp / gson / json 等 jar 在 fpk-src/ui/lib 下：Spider.safeDns() 要引用 okhttp3.Dns，
# 不带 classpath 编译会 NoSuchSymbol（之前只有 hub/build.sh 加了，这里漏了）
LIBDIR="$(cd .. && pwd)/fpk-src/ui/lib"
CP=""
if [ -d "$LIBDIR" ]; then CP="$(ls "$LIBDIR"/*.jar 2>/dev/null | tr '\n' ':')"; fi
javac -encoding UTF-8 ${CP:+-cp "$CP"} -d out $(find src -name '*.java')
echo "编译完成: $(find out -name '*.class' | wc -l) 个 class"

cat <<'EOF'

用法：
  export SPIDER_EXT='{"site":["https://xxx"]}'
  java -Xverify:none -cp "out:$JAR/lmw-classes.jar:$JAR/lib/*" \
       SpiderRunner com.github.catvod.spider.AiKan home

批量探测：
  python3 probe_spiders.py <子仓配置URL或本地json> [最多测几个]
EOF
