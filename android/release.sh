#!/bin/bash
# 发布新版应用（应用内更新会读这里生成的 tv-app.json）：
#   1. 先把 AndroidManifest.xml 里的 versionCode 加 1、versionName 改成新版本号
#   2. ./release.sh "这一版改了什么"
# 会构建、写 release/BiliPicks-tv.apk 和 release/tv-app.json；
# 如果设置了 SITE_DIR（放 tv.html 的那个目录），也复制一份过去，网站上线后电视上就能「检查更新」。
#   SITE_DIR=../../tmp/site/bili ./release.sh "加了快进"
set -euo pipefail
cd "$(dirname "$0")"
NOTES=${1:-}
./build.sh
CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' AndroidManifest.xml)
NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
mkdir -p release
cp BiliPicks-tv.apk release/BiliPicks-tv.apk
SHA=$(shasum -a 256 release/BiliPicks-tv.apk | cut -d' ' -f1)
SIZE=$(wc -c < release/BiliPicks-tv.apk | tr -d ' ')
python3 - "$CODE" "$NAME" "$SHA" "$SIZE" "$NOTES" > release/tv-app.json <<'PY'
import json, sys
code, name, sha, size, notes = sys.argv[1:]
print(json.dumps({"versionCode": int(code), "versionName": name, "apk": "BiliPicks-tv.apk",
                  "sha256": sha, "size": int(size), "notes": notes}, ensure_ascii=False, indent=2))
PY
echo "release/：BiliPicks-tv.apk ${NAME}（versionCode ${CODE}，${SIZE} 字节）"
if [ -n "${SITE_DIR:-}" ]; then
  cp release/BiliPicks-tv.apk release/tv-app.json "$SITE_DIR/"
  echo "已复制到 ${SITE_DIR}，记得提交、上线网站"
fi
