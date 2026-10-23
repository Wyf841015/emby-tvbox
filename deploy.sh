#!/bin/bash
# 部署 hub.jar + index.html + 插件 DLL + cmd 脚本，然后重启应用与 Emby。
# Emby 应用名不写死：自动找「programdata 里有 plugins 目录」的那个（EmbyServer4-9 / Emby-crack 都见过）。
set -u
R=/vol3/1000/hermes/emby-tvbox
A=/vol3/@appcenter/tvboxhub

install -m 644 -o root -g root "$R/fpk-src/ui/hub.jar"    "$A/ui/hub.jar"
install -m 644 -o root -g root "$R/fpk-src/ui/index.html" "$A/ui/index.html"

for f in main run-hub.sh install_callback upgrade_callback; do
  [ -f "$R/fpk-src/cmd/$f" ] || continue
  [ -d /var/apps/tvboxhub/cmd ] && install -m 755 -o root -g root "$R/fpk-src/cmd/$f" "/var/apps/tvboxhub/cmd/$f"
done

# ── 找活着的 Emby 应用 ──
EMBY_APP=""
EMBY_PD=""
for d in /vol3/@appdata/Emby*; do
  [ -d "$d/plugins" ] || continue
  n=$(basename "$d")
  if [ -d "/vol3/@appcenter/$n" ]; then EMBY_APP="$n"; EMBY_PD="$d"; break; fi
done
if [ -z "$EMBY_APP" ]; then
  echo "!! 没找到 Emby 应用（@appdata/Emby*/plugins），跳过插件部署" >&2
else
  ref="$EMBY_PD/plugins/TvBox.dll"
  [ -f "$ref" ] || ref="$EMBY_PD/plugins"
  cu=$(stat -c %U "$ref"); cg=$(stat -c %G "$ref")
  install -m 664 -o "$cu" -g "$cg" "$R/fpk-src/ui/plugin/TvBox.dll" "$EMBY_PD/plugins/TvBox.dll"
  echo "插件已部署: $EMBY_APP ($cu:$cg)  sha256=$(sha256sum "$EMBY_PD/plugins/TvBox.dll" | cut -d' ' -f1)"
fi

appcenter-cli stop tvboxhub >/dev/null 2>&1; sleep 2
appcenter-cli start tvboxhub >/dev/null 2>&1; echo "tvboxhub restarted"

if [ -n "$EMBY_APP" ]; then
  appcenter-cli stop "$EMBY_APP" >/dev/null 2>&1; sleep 4
  appcenter-cli start "$EMBY_APP" >/dev/null 2>&1; echo "$EMBY_APP restarted"
fi
