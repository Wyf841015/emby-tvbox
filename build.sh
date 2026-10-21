#!/usr/bin/env bash
# 构建 Emby TVBox 插件。
#
# 需要参照 Emby 自己的程序集编译，所以脚本会先确保 lib/emby4_10/ 下有参考 DLL：
#   1) 已存在 → 直接用
#   2) 没有但 .dev/emby/ 有解包后的 Emby → 从那里复制
#   3) 都没有 → 自动下载官方 deb 并解包（只解包，不安装）
set -euo pipefail

cd "$(dirname "$0")"

EMBY_VERSION="${EMBY_VERSION:-4.10.2.0}"
LIB_DIR="lib/emby4_10"
DEV_DIR=".dev"
DEB="$DEV_DIR/emby.deb"
NEED_DLLS=(MediaBrowser.Common MediaBrowser.Controller MediaBrowser.Model System.IO.Pipelines)

have_all=1
for d in "${NEED_DLLS[@]}"; do
  [ -f "$LIB_DIR/$d.dll" ] || have_all=0
done

if [ "$have_all" -eq 0 ]; then
  mkdir -p "$LIB_DIR" "$DEV_DIR"

  if [ ! -f "$DEV_DIR/emby/opt/emby-server/system/MediaBrowser.Controller.dll" ]; then
    if [ ! -f "$DEB" ]; then
      echo "==> 下载 Emby Server ${EMBY_VERSION} (deb, 约 235MB)"
      curl -fL --http1.1 --retry 3 -C - \
        -o "$DEB" \
        "https://github.com/MediaBrowser/Emby.Releases/releases/download/${EMBY_VERSION}/emby-server-deb_${EMBY_VERSION}_amd64.deb"
    fi
    echo "==> 解包 deb（不安装）"
    mkdir -p "$DEV_DIR/emby"
    dpkg-deb -x "$DEB" "$DEV_DIR/emby"
  fi

  SYS="$DEV_DIR/emby/opt/emby-server/system"
  for d in "${NEED_DLLS[@]}"; do
    if [ -f "$SYS/$d.dll" ]; then
      cp -f "$SYS/$d.dll" "$LIB_DIR/"
      echo "  参考程序集: $d.dll"
    else
      echo "!! 缺少 $SYS/$d.dll" >&2
      exit 1
    fi
  done
fi

echo "==> 编译"
export DOTNET_ROOT="${DOTNET_ROOT:-/usr/share/dotnet}"
DOTNET="${DOTNET:-$DOTNET_ROOT/dotnet}"
[ -x "$DOTNET" ] || DOTNET="dotnet"

"$DOTNET" build TvBox.Plugin/TvBox.Plugin.csproj -c Release --nologo

OUT="TvBox.Plugin/bin/Release/TvBox.dll"
echo
echo "==> 产物: $OUT ($(du -h "$OUT" | cut -f1))"
echo
echo "安装到 Emby（把 TvBox.dll 放进 programdata/plugins/ 后重启 Emby）:"
echo "  cp \"$OUT\" /path/to/emby/programdata/plugins/"
