#!/bin/bash
# 发布新版应用（应用内更新会读这里生成的 tv-app.json）：
#   1. 先把 AndroidManifest.xml 里的 versionCode 加 1、versionName 改成新版本号
#   2. ./release.sh "这一版改了什么"
# 会构建、写 release/BiliPicks-tv.apk 和 release/tv-app.json；
# 再复制一份到网站上放 tv.html 的目录（SITE_DIR），网站上线后电视上就能「检查更新」。
# 电视一般不挂代理、连不上 GitHub，所以应用内更新只从网站下载，每次发版都要复制过去。
# SITE_DIR 可以写在 android/.site_dir 里（不进仓库），以后就不用每次带：
#   echo /path/to/site/bili > .site_dir
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${SITE_DIR:-}" ] && [ -f .site_dir ]; then SITE_DIR=$(cat .site_dir); fi
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
else
  echo "注意：没设 SITE_DIR，网站上的安装包没更新，电视上的「检查更新」看不到这一版"
fi
