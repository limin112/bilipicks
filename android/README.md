# BiliPicks 电视版应用（安卓电视 / 投影仪）

一个很小的安卓应用（约 130KB）：全屏打开 BiliPicks 的电视版页面 `tv.html`，用遥控器操作。在极米 H3S（安卓 9，内核 Chrome 66）上实测过。

这是个研究和自用的项目。想直接试，可以下载打好的安装包；更欢迎 clone 下来自己构建、自己装，按自家的需要改：换成你自己的清单网站、换图标、改遥控器的按键。整个应用就一个 Java 文件，不需要 Android Studio。

## 直接下载安装包

[**BiliPicks-tv-1.1.apk**](https://github.com/limin112/bilipicks/raw/main/android/release/BiliPicks-tv-1.1.apk)（70KB，安卓 5.0 以上，打开的是 <https://minlibuilds.com/bili/tv.html>）

SHA-256：`7c9b06e08f45ae0e81609af2435e10a012dd9c03dddff669d5d781aac3daf195`

- 先在电视的设置里打开「允许安装未知来源应用」，再用 U 盘拷过去，在文件管理器里点开安装；或者用下面的 adb 方法：`adb install BiliPicks-tv-1.1.apk`。
- 只能用遥控器操作（方向键、OK、返回），所以适合电视、投影仪、电视盒子，不适合手机平板。
- 网站上的视频清单更新了，电视上直接就是新的，不用重装。
- 以后自己构建的版本签名不一样，装之前要先卸载这个。
- 版本：1.1 加了快进 / 快退（◀ ▶），换视频改用 ▼ 选集；1.0 的 ◀ ▶ 是换上一条 / 下一条。装了 1.0 的直接覆盖安装 1.1，看完进度不会丢。

- 打开就是电视版：上面是「豆腐块」（继续闯关、9 关、随便看看），下面一排一排是各关和各分类的视频。
- 遥控器：方向键挑，OK 播放；播放时 ◀ ▶ 快退 / 快进（一下 5 秒，按住连续），OK 暂停 / 继续，▲ 标记看完，▼ 选集（换一条），返回键回首页；首页连按两次返回退出。遥控器上有上一曲 / 下一曲键的，也能直接换。
- 打开视频直接开播，不用先点一下画面。
- 遥控器按键都由应用转给页面，焦点不会跑进 B 站播放器里，点不到播放器里的推荐视频；页面也只能在自己的网站里跳转。
- 视频和路线是网页上的内容，网站更新后电视上直接就是新的，**不用重装应用**。

## 自己构建并装到电视上

### 1. 打开电视的 ADB 调试

一般在「设置 → 关于 → 版本号」上连按 7 次确认键打开开发者选项，再打开「USB 调试 / 网络调试」。

极米投影仪的系统菜单里没有开发者选项，可以用第三方的「极米 ADB 开启器」（[code-ant/z7x-adb](https://github.com/code-ant/z7x-adb)）打开网络 ADB，它能设成开机自动恢复。

电脑和电视要能互相访问：在同一个 Wi‑Fi 里，而且路由器没开「AP 隔离 / 访客网络」。电脑上 `ping 电视的IP` 不通时先查这个。

### 2. 准备工具（只需一次）

不需要 Android Studio 和 Gradle。macOS 上：

```bash
brew install --cask android-platform-tools android-commandlinetools   # adb、sdkmanager
sdkmanager --sdk_root=$HOME/Library/Android/sdk "platforms;android-34" "build-tools;34.0.0"
```

还需要 JDK 17 以上（`java -version` 看一下）。下载慢可以给 `sdkmanager` 加 `--proxy=http --proxy_host=127.0.0.1 --proxy_port=端口`。

### 3. 构建并安装

```bash
adb connect 192.168.0.106:5555           # 换成你电视的 IP；电视上弹出「允许调试」选「始终允许」
DEVICE=192.168.0.106:5555 ./build.sh install
```

装好后在电视的应用列表里找 BiliPicks。只想要安装包（用 U 盘装）就运行 `./build.sh`，产物是 `BiliPicks-tv.apk`。

签名钥匙默认放在 `~/.android/bilipicks-tv.keystore`（第一次构建时自动生成，不进仓库）。**以后升级要用同一把钥匙**，换了钥匙要先在电视上卸载旧版。

## 改成你自己的网站

改 `src/com/minlibuilds/bilipicks/MainActivity.java` 里的 `HOME`（电视版地址）和 `SITE_HOST`（允许跳转的域名），重新构建。图标在 `res/`，用 `python3 tools/make_icons.py` 生成（需要 Pillow）。

## 几个坑（都已经处理了）

- **老 WebView 的视频封面**：设置了 `WebChromeClient` 之后，页面里一出现 `<video>`，老 WebView 会因为默认封面是 null 而崩。`getDefaultVideoPoster()` 返回一个 1×1 的图。
- **d8 编译崩溃**：JDK 21 的 javac 会给非静态内部类的构造器、泛型桥接方法写出没有名字的 `MethodParameters`，build-tools 34 的 d8 读到就 NullPointerException。所以代码里只用静态内部类，`ValueCallback` 不带泛型。
- **暂停**：B 站播放器在跨域 iframe 里，网页没法直接控制。页面把焦点给 iframe 后返回 `"space"`，应用往 WebView 里补按一个空格键。

## 调试

极米这类机器把 logcat 关了，看不到网页报错。应用开着 WebView 远程调试，可以这样连进去：

```bash
adb -s $DEVICE forward tcp:9222 localabstract:$(adb -s $DEVICE shell cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | tail -1)
node tools/cdp.mjs 'location.href' 3          # 执行一段 JS，顺便打印 3 秒内的控制台输出和报错
adb -s $DEVICE exec-out screencap -p > shot.png   # 截屏（有的机器要先 screencap 到 /sdcard 再 pull）
```

开发时可以让应用打开局域网里的页面（只放行自己的网站和 `192.168.*` / `10.*`）：

```bash
python3 -m http.server 8765        # 在 tv.html 所在目录
adb -s $DEVICE shell am start -n com.minlibuilds.bilipicks/.MainActivity --es url http://电脑IP:8765/tv.html
```

用 `adb shell input keyevent DPAD_RIGHT`（`DPAD_CENTER`、`BACK` …）模拟遥控器。
