#!/bin/bash
# hub 进程守护
#
# 为什么要它：2026-10-22 生产实例在 G1 GC 的 SATB 队列里 SIGSEGV 直接崩了
# （hs_err: G1SATBMarkQueueSet::filter，Celeron J3355 / 2 核 / 512m 堆），
# 整个过程没有任何 Java 异常，日志最后一句话是正常的请求处理。
# fnOS 侧的 PID 文件还在、进程没了 → 应用显示"已启用"但网关 502，非常难查。
#
# 两道保险：
#   1) 主进程启动参数改用 -XX:+UseSerialGC（2 核小内存场景 SerialGC 反而更快，
#      也绕开 G1 SATB 这条崩溃路径），并加 -XX:+ExitOnOutOfMemoryError
#   2) 这个脚本负责看住 java：无论什么原因退出（崩溃/被杀/OOM），只要不是正常
#      退出码 0，就等 5 秒重新拉起，并把原因写进 info.log
#
# 用法（由 cmd/main 调用）：
#   bash run-hub.sh <java> <app_dir> <pkgvar> <log_file> <spider_jar>
set -u

JAVA="$1"
APP="$2"
VARDIR="$3"
LOG="$4"
SPIDER_JAR="${5:-}"
PORT=8098
SOCK="${APP}/tvboxhub.sock"
DELAY="${HUB_RESTART_DELAY:-5}"

child=""
term() {
    if [ -n "$child" ] && kill -0 "$child" 2>/dev/null; then
        kill -TERM "$child" 2>/dev/null
        wait "$child" 2>/dev/null
    fi
    exit 0
}
trap term TERM INT

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') - [supervisor] $*" >> "$LOG"; }

# 日志轮转：supervisor 和 hub 都往 info.log 追加，长期不轮转会顶满小分区
rotate_log() {
    [ -f "$LOG" ] || return 0
    size=$(stat -c%s "$LOG" 2>/dev/null || echo 0)
    if [ "$size" -gt 10485760 ]; then
        [ -f "$LOG.2" ] && mv -f "$LOG.2" "$LOG.3"
        [ -f "$LOG.1" ] && mv -f "$LOG.1" "$LOG.2"
        mv -f "$LOG" "$LOG.1"
        log "日志超过 10MB，已轮转为 $LOG.1"
    fi
}

fails=0
while true; do
    rotate_log
    log "启动 hub（SerialGC, pid 待定）"
    # -Xverify:none 必须保留：TVBox 生态的部分 spider jar 里有不合规的 class
    #（实测 ClassFormatError: Bad string initial value in class file com/github/catvod/spider/Init），
    # 打开校验会让这些站点整站不可用。作为补偿，远程 jar 现在强制校验配置里的 ;md5;（Hub.downloadJar）。
    # 内存：小堆起步 + 空闲时把归还给系统（配 Hub 里的空闲 GC）
    #   -Xms32m 起步不让 JVM 一开机就吃掉一大块
    #   MaxHeapFreeRatio=40 → GC 后空闲超过 40% 就把内存还给 OS（SerialGC 认这个）
    #   ReservedCodeCacheSize 限住 JIT 代码缓存（默认能长到 240M）
    # RSS 实测构成（133MB 里 99MB 是匿名内存）：堆只占 31MB，剩下是元空间 +
    # JIT 代码缓存 + 线程栈 + glibc 分配器 arena。逐项压：
    #   MALLOC_ARENA_MAX=2  → 多线程下 glibc 默认每线程一个 arena，白白吃掉几十兆
    #   -Xss512k            → 线程栈默认 1M，49 个线程省一半
    #   TieredStopAtLevel=1 → 只用 C1 编译器，代码缓存小一截（spider 干的是 HTTP/JSON，不吃峰值 JIT）
    export MALLOC_ARENA_MAX=2
    "$JAVA" -Xverify:none -Xms32m -Xmx320m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError \
        -XX:MinHeapFreeRatio=15 -XX:MaxHeapFreeRatio=40 -XX:ReservedCodeCacheSize=48m \
        -XX:TieredStopAtLevel=1 -XX:MaxMetaspaceSize=176m -Dfile.encoding=UTF-8 \
        -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
        -Dtvbox.panDir="${VARDIR}/data/pan" \
        -cp "${APP}/ui/hub.jar:${APP}/ui/lib/*" hub.Hub \
        --port "$PORT" --socket "$SOCK" --prefix /app/tvboxhub \
        --config "${VARDIR}/hub.json" --jar-dir "${APP}/ui/lib" \
        --plugin-dll "${APP}/ui/plugin/TvBox.dll" --ui-dir "${APP}/ui" \
        ${SPIDER_JAR:+--spider-jar "$SPIDER_JAR"} >> "$LOG" 2>&1 &
    child=$!
    log "pid=$child"

    wait "$child"
    code=$?
    log "hub 退出 exit=$code"
    if [ "$code" -eq 0 ]; then
        log "正常退出，supervisor 结束"
        exit 0
    fi
    # 崩溃后重启前把残留 socket 清掉，否则新进程 bind 会失败
    [ -S "$SOCK" ] && rm -f "$SOCK"
    # 指数退避 + 连续失败上限：以前固定 5 秒无限重启，包损坏时会把 info.log 刷满并白烧 CPU
    fails=$((fails + 1))
    if [ "$fails" -ge 8 ]; then
        log "连续 ${fails} 次异常退出，停止自动重启（请检查 info.log / hs_err_pid*.log）"
        exit 1
    fi
    backoff=$(( DELAY * (1 << (fails - 1)) ))
    [ "$backoff" -gt 120 ] && backoff=120
    log "${backoff} 秒后重新拉起（连续失败 ${fails} 次）"
    sleep "$backoff"
done
