#!/bin/bash
# BiliPicks 电视版：构建 APK，可选通过 Wi-Fi 装到电视 / 投影仪。不需要 Android Studio 和 Gradle。
#   ./build.sh                      只构建，产物 BiliPicks-tv.apk
#   ./build.sh install              构建并装到 $DEVICE（默认 192.168.0.106:5555），然后打开
#   DEVICE=192.168.1.20:5555 ./build.sh install
# 需要：JDK 17+、Android SDK 的 build-tools;34.0.0 和 platforms;android-34（见 README）、adb。
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
DEVICE=${DEVICE:-192.168.0.106:5555}
# 签名用的钥匙放在仓库外面。同一台电视以后升级要用同一把钥匙，别弄丢。
KEYSTORE=${KEYSTORE:-$HOME/.android/bilipicks-tv.keystore}
KS_PASS=${KS_PASS:-bilipicks}
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home)}
for f in "$BT/aapt2" "$JAR"; do [ -e "$f" ] || { echo "缺少 $f，先按 README 装 Android SDK"; exit 1; }; done

rm -rf build && mkdir -p build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR" -d build/classes $(find src -name '*.java')
(cd build/classes && jar cf ../classes.jar .)
"$BT/d8" --release --min-api 21 --lib "$JAR" --output build/dex build/classes.jar
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias bilipicks \
    -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=BiliPicks TV" >/dev/null 2>&1
  echo "新建了签名钥匙：$KEYSTORE"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias bilipicks --out BiliPicks-tv.apk build/aligned.apk
rm -f BiliPicks-tv.apk.idsig
echo "构建完成：$(pwd)/BiliPicks-tv.apk（$(du -h BiliPicks-tv.apk | cut -f1 | tr -d ' ')）"

if [ "${1:-}" = install ]; then
  adb connect "$DEVICE" >/dev/null
  adb -s "$DEVICE" install -r BiliPicks-tv.apk
  adb -s "$DEVICE" shell am start -n com.minlibuilds.bilipicks/.MainActivity >/dev/null
  echo "已装到 $DEVICE 并打开"
fi
