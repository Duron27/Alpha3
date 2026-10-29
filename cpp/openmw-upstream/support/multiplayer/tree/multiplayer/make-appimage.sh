#!/bin/sh
# Turns the portable client zip (client-package / container build) into one AppImage file.
#
#   multiplayer/make-appimage.sh <openmw-multiplayer-client-...-portable.zip> [output.AppImage]
#
# Double-click or run it: the launcher starts. Another program: ./OpenMW-Multiplayer.AppImage openmw-cs
# (openmw, openmw-launcher, openmw-wizard, openmw-cs, bsatool, esmtool, niftest, ... as in the zip).
# Needs appimagetool (downloaded if it is not on PATH). The AppImage uses the static type 2 runtime: no FUSE needed to build or run.
set -eu
ZIP=${1:?usage: make-appimage.sh <portable zip> [output]}
OUT=${2:-OpenMW-Multiplayer-$(uname -m).AppImage}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
OUT=$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")

TOOL=$(command -v appimagetool || true)
if [ -z "$TOOL" ]; then
    TOOL=$WORK/appimagetool
    curl -fsSL -o "$TOOL" "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$(uname -m).AppImage"
    chmod +x "$TOOL"
fi

unzip -q "$ZIP" -d "$WORK"
APPDIR=$WORK/AppDir
mv "$WORK/openmw-multiplayer-client" "$APPDIR"
cp "$APPDIR/openmw.png" "$APPDIR/.DirIcon"

cat > "$APPDIR/AppRun" <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
PROGRAM=openmw-launcher
case "${1:-}" in
    openmw|openmw-launcher|openmw-wizard|openmw-cs|bsatool|esmtool|niftest|openmw-iniimporter|openmw-essimporter|openmw-navmeshtool|openmw-bulletobjecttool)
        PROGRAM=$1; shift ;;
esac
exec "$HERE/$PROGRAM" "$@"
RUN
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/openmw-multiplayer.desktop" <<'DESK'
[Desktop Entry]
Type=Application
Name=OpenMW Multiplayer
GenericName=Role Playing Game
Comment=OpenMW with the multiplayer mod built in
Exec=AppRun
Icon=openmw
Categories=Game;RolePlaying;
DESK

ARCH=$(uname -m) "$TOOL" --appimage-extract-and-run "$APPDIR" "$OUT"
chmod +x "$OUT"
echo "Made $OUT"
