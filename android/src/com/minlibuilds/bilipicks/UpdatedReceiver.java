package com.minlibuilds.bilipicks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 应用自己被新版覆盖安装后（MY_PACKAGE_REPLACED），重新打开，省得在桌面上再找一次 */
public class UpdatedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        Intent open = new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(open);
    }
}
