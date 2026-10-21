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

while true; do
    rotate_log
    log "启动 hub（SerialGC, pid 待定）"
    "$JAVA" -Xverify:none -Xmx512m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError \
        -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8 \
        -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
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
    log "${DELAY} 秒后重新拉起（若是 JVM 段错误，hs_err_pid*.log 会在当前工作目录）"
    sleep "$DELAY"
done
