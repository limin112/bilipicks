package com.minlibuilds.bilipicks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Parcelable;

/** 接 PackageInstaller 的结果：需要用户确认就弹系统的安装确认框；失败就告诉页面 */
public class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Parcelable p = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (p instanceof Intent) {
                Intent confirm = (Intent) p;
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(confirm);
            }
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            MainActivity a = MainActivity.current;
            if (a != null) a.updateState("error", st == PackageInstaller.STATUS_FAILURE_ABORTED ? "取消了安装" : "安装没成功" + (msg == null ? "" : "：" + msg));
        }
    }
}
