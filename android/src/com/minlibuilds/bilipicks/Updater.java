package com.minlibuilds.bilipicks;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/**
 * 应用内更新：电视版页面上按「更新」→ 页面调 BiliPicksApp.update() → 在后台线程里跑这个。
 *
 * 1. 读和电视版页面同一目录下的 tv-app.json：{"versionCode", "versionName", "apk", "sha256", "notes"}
 * 2. 比当前版本新，就下载 apk（只认和 tv-app.json 同一个网站的 https 地址），边下边算 SHA-256
 * 3. 校验：SHA-256 对得上、包名一样、版本号更大、签名和当前装的一样
 * 4. 用系统 PackageInstaller 安装。普通应用不能静默安装，系统会弹一个确认框，遥控器按「安装」；
 *    装完 UpdatedReceiver 收到 MY_PACKAGE_REPLACED，把应用重新打开。
 *
 * 进度通过 MainActivity.updateState(状态, 参数) 回到页面的 tvUpdateState()：
 * checking / latest / download(百分比) / verify / permission / install / error(原因)
 */
final class Updater implements Runnable {
    static volatile boolean running;
    final MainActivity a;

    Updater(MainActivity a) { this.a = a; }

    /** tv-app.json 和电视版页面放在同一个目录 */
    static String manifestUrl() {
        String home = MainActivity.HOME;
        return home.substring(0, home.lastIndexOf('/') + 1) + "tv-app.json";
    }

    @Override public void run() {
        try {
            work();
        } catch (Exception e) {
            a.updateState("error", e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            running = false;
        }
    }

    void work() throws Exception {
        a.updateState("checking", "");
        URL mu = new URL(manifestUrl() + "?t=" + System.currentTimeMillis());
        JSONObject m = new JSONObject(new String(get(mu), "UTF-8"));
        int code = m.getInt("versionCode");
        int cur = a.versionCode();
        if (code <= cur) { a.updateState("latest", a.versionName()); return; }

        URL apk = new URL(mu, m.getString("apk"));
        if (!"https".equals(apk.getProtocol()) || !apk.getHost().equals(mu.getHost())) throw new Exception("安装包地址不对");
        String want = m.getString("sha256").toLowerCase(Locale.ROOT);

        File f = new File(a.getCacheDir(), "update.apk");
        String got = download(apk, f);
        a.updateState("verify", "");
        if (!got.equals(want)) throw new Exception("安装包校验没通过，可能没下载完整，再按一次试试");

        PackageManager pm = a.getPackageManager();
        String pkg = a.getPackageName();
        PackageInfo neu = pm.getPackageArchiveInfo(f.getPath(), PackageManager.GET_SIGNATURES);
        if (neu == null || !pkg.equals(neu.packageName)) throw new Exception("安装包不是 BiliPicks");
        if (neu.versionCode <= cur) { a.updateState("latest", a.versionName()); return; }
        Signature[] mine = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
        if (neu.signatures != null && !Arrays.equals(neu.signatures, mine)) {
            throw new Exception("新版的签名和现在装的不一样，要先卸载再装");
        }

        // 安卓 8 起，应用要先被允许「安装未知应用」。第一次会打开设置页，打开开关后回来再按一次
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            a.updateState("permission", "");
            a.openInstallPermissionSettings();
            return;
        }

        PackageInstaller pi = pm.getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(pkg);
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream out = s.openWrite("base.apk", 0, f.length());
            InputStream in = new FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            s.fsync(out);
            out.close();
            Intent done = new Intent(a, InstallReceiver.class);
            PendingIntent p = PendingIntent.getBroadcast(a, id, done, PendingIntent.FLAG_UPDATE_CURRENT);
            a.updateState("install", m.optString("versionName"));
            s.commit(p.getIntentSender());
        } finally {
            s.close();
        }
    }

    static HttpURLConnection open(URL u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Cache-Control", "no-cache");
        if (c.getResponseCode() != 200) throw new Exception("网站返回 " + c.getResponseCode());
        return c;
    }

    static byte[] get(URL u) throws Exception {
        HttpURLConnection c = open(u);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    /** 下载到文件，顺便算 SHA-256（十六进制小写），每涨 5% 报一次进度 */
    String download(URL u, File f) throws Exception {
        HttpURLConnection c = open(u);
        long total = c.getContentLength();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = c.getInputStream();
        OutputStream out = new FileOutputStream(f);
        byte[] buf = new byte[65536];
        long done = 0;
        int last = -1, n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            md.update(buf, 0, n);
            done += n;
            int pct = total > 0 ? (int) (done * 100 / total) : 0;
            if (pct / 5 != last) { last = pct / 5; a.updateState("download", String.valueOf(pct)); }
        }
        in.close();
        out.close();
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
