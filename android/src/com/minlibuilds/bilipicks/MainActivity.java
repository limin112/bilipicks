package com.minlibuilds.bilipicks;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

/**
 * BiliPicks 电视版的安卓外壳：全屏 WebView 打开网站上的 bili/tv.html。
 *
 * 遥控器按键不直接交给网页，而是转成 tvKey('left' / 'ok' / 'back' …) 调页面里的函数：
 * 这样焦点永远不会掉进 B 站播放器的 iframe 里，孩子也点不到播放器里的推荐视频。
 * 页面返回 "exit" 就退出应用，返回 "space" / "seek+" / "seek-" 就往播放器里按一下空格 / → / ←（暂停、快进、快退）。
 *
 * 页面里还能用 BiliPicksApp.versionCode() / versionName() / update()：主页右下角「设置」里的应用更新，见 Updater。
 */
public class MainActivity extends Activity {
    static final String HOME = "https://minlibuilds.com/bili/tv.html";
    static final String SITE_HOST = "minlibuilds.com";
    // 用安卓 UA 时 B 站嵌入播放器会退回 mp4 直链，CDN 返回 403；用电脑 UA 它会走 DASH，能正常播。
    // 末尾的 BiliPicksTV/版本号（versionCode）让页面知道自己在应用里、应用能做什么（比如 ≥2 能快进）。
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/66.0.3359.158 Safari/537.36 BiliPicksTV/";

    static MainActivity current;  // InstallReceiver 要把安装结果告诉页面

    WebView web;
    FrameLayout root;
    String startUrl = HOME;
    boolean offline;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 留着远程调试：电脑上 adb forward 之后能用 Chrome DevTools 看页面（见 README）
        WebView.setWebContentsDebuggingEnabled(true);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B1220);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1220);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        web.requestFocus(); // 按键在 dispatchKeyEvent 里就截下转给页面了；WebView 要有焦点，补的空格才进得去

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // 看完进度存在 localStorage
        s.setMediaPlaybackRequiresUserGesture(false); // 打开就播，不用先点一下
        s.setUserAgentString(UA + versionCode());
        s.setUseWideViewPort(true);             // 按页面的 <meta viewport> 排版，不自动缩小去塞超宽的内容
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);                     // 不跟系统字体大小走，电视版自己按屏幕宽度定字号

        web.setWebViewClient(new Client(this));
        web.setWebChromeClient(new Chrome());
        web.addJavascriptInterface(new AppBridge(this), "BiliPicksApp");

        String extra = getIntent().getStringExtra("url");
        if (extra != null && allowed(Uri.parse(extra))) startUrl = extra;
        web.loadUrl(startUrl);
    }

    /** 只在自己的网站里打转；开发时允许局域网地址（adb shell am start … --es url http://192.168.x.x:8765/tv.html） */
    static boolean allowed(Uri u) {
        String host = u.getHost();
        if (host == null) return false;
        if (host.equals(SITE_HOST) || host.endsWith("." + SITE_HOST)) return true;
        return "http".equals(u.getScheme()) && (host.startsWith("192.168.") || host.startsWith("10."));
    }

    // ---------- 遥控器 ----------
    static String keyName(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return "ok";
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE: return "back";
            case KeyEvent.KEYCODE_MENU: return "menu";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return "playpause";
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return "next";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND: return "prev";
            default: return null;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        // 自己补按给播放器的键，播放器没「吃掉」（比如 B 站播放器处理完方向键不 preventDefault），
        // 老 WebView 会把它当成没人处理的键再交回 Activity。认出来丢掉，不然快进一下会转好几圈。
        if (e.getScanCode() == SYNTH_SCAN) return true;
        String name = keyName(e.getKeyCode());
        if (name == null) return super.dispatchKeyEvent(e); // 音量键等交给系统
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        // 确认键、返回键长按不重复触发；方向键按住可以连续移动
        if (e.getRepeatCount() > 0 && (name.equals("ok") || name.equals("back") || name.equals("menu"))) return true;
        if (offline) {
            if (name.equals("ok")) { offline = false; web.loadUrl(startUrl); }
            else if (name.equals("back")) finish();
            return true;
        }
        web.evaluateJavascript("window.tvKey ? tvKey('" + name + "') : 'none'", (ValueCallback<String>) new KeyResult(this, name, e));
        return true;
    }

    // 注意：这里故意用不带泛型的 ValueCallback，也不用匿名类 / 非静态内部类。
    // JDK 21 的 javac 会给泛型桥接方法、内部类构造器写出没有名字的 MethodParameters，build-tools 34 的 d8 读到就崩。
    @SuppressWarnings({"rawtypes", "unchecked"})
    static class KeyResult implements ValueCallback {
        final MainActivity a; final String key; final KeyEvent ev;
        KeyResult(MainActivity a, String key, KeyEvent ev) { this.a = a; this.key = key; this.ev = ev; }
        @Override public void onReceiveValue(Object o) {
            String r = String.valueOf(o);
            boolean noTv = "\"none\"".equals(r) || "null".equals(r); // 不是电视版页面（比如网页版），按普通浏览器处理
            if ("\"exit\"".equals(r)) {
                a.finish();
            } else if ("\"space\"".equals(r)) {
                a.pressKey(KeyEvent.KEYCODE_SPACE);
            } else if ("\"seek+\"".equals(r)) {
                a.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT);
            } else if ("\"seek-\"".equals(r)) {
                a.pressKey(KeyEvent.KEYCODE_DPAD_LEFT);
            } else if (noTv && "back".equals(key)) {
                if (a.web.canGoBack()) a.web.goBack(); else a.finish();
            } else if (noTv) {
                a.web.dispatchKeyEvent(ev);
                a.web.dispatchKeyEvent(KeyEvent.changeAction(ev, KeyEvent.ACTION_UP));
            }
        }
    }

    /**
     * 页面已经把焦点放到播放器 iframe 上，这里往 WebView 里补按一个键（不经过上面的 dispatchKeyEvent）：
     * 空格 = 暂停 / 继续，→ / ← = 快进 / 快退 5 秒。这是 B 站播放器自己的键盘快捷键。
     */
    static final int SYNTH_SCAN = 0xB1B1; // 补按的键用这个扫描码做记号，见 dispatchKeyEvent

    void pressKey(int code) {
        long t = SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, SYNTH_SCAN));
        web.dispatchKeyEvent(new KeyEvent(t, t + 30, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, SYNTH_SCAN));
    }

    // ---------- 应用内更新 ----------
    int versionCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    /** 从任意线程把更新进度告诉页面：tvUpdateState(状态, 参数) */
    void updateState(String state, String arg) {
        runOnUiThread(new Js(this, "window.tvUpdateState && tvUpdateState(" + JSONObject.quote(state) + "," + JSONObject.quote(arg) + ")"));
    }

    /** 打开「允许安装未知应用」的设置页；电视上没有这一页就退到「安全」设置 */
    void openInstallPermissionSettings() {
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
        try { startActivity(i); return; } catch (ActivityNotFoundException e) { /* 换下一个 */ }
        try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); } catch (ActivityNotFoundException e) {
            updateState("error", "这台设备找不到「允许安装未知应用」的设置，只能用 U 盘或 adb 装新版");
        }
    }

    static class Js implements Runnable {
        final MainActivity a; final String code;
        Js(MainActivity a, String code) { this.a = a; this.code = code; }
        @Override public void run() { if (a.web != null) a.web.evaluateJavascript(code, null); }
    }

    /** 给页面用的接口。B 站播放器的 iframe 也能看到它，所以 update() 不收参数：下载地址只从自己网站的 tv-app.json 读 */
    static class AppBridge {
        final MainActivity a;
        AppBridge(MainActivity a) { this.a = a; }
        @JavascriptInterface public int versionCode() { return a.versionCode(); }
        @JavascriptInterface public String versionName() { return a.versionName(); }
        @JavascriptInterface public void update() {
            if (Updater.running) return;
            Updater.running = true;
            new Thread(new Updater(a), "bilipicks-update").start();
        }
    }

    // ---------- 网页 ----------
    static class Client extends WebViewClient {
        final MainActivity a;
        Client(MainActivity a) { this.a = a; }

        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            // 播放器里的「去 B 站看」「相关推荐」之类都拦下，不离开 BiliPicks
            return req.isForMainFrame() && !allowed(req.getUrl());
        }

        @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
            if (!req.isForMainFrame()) return;
            a.offline = true;
            String html = "<html><body style='margin:0;background:#0b1220;color:#c3cede;font:28px sans-serif;"
                    + "display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh'>"
                    + "<b style='font-size:40px;color:#fff;margin-bottom:20px'>连不上网</b>"
                    + "检查一下投影仪的网络，然后按 OK 重试</body></html>";
            v.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        }
    }

    static class Chrome extends WebChromeClient {
        // 老 WebView 在设置了 WebChromeClient 之后，默认视频封面是 null，页面里一出现 <video> 就崩
        @Override public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String extra = i.getStringExtra("url");
        if (extra != null && allowed(Uri.parse(extra))) { startUrl = extra; offline = false; web.loadUrl(extra); }
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onDestroy() {
        if (current == this) current = null;
        root.removeView(web);
        web.destroy();
        web = null;
        super.onDestroy();
    }
}
