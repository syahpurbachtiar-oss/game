package com.bokir.wake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            boolean auto = context.getSharedPreferences("bokir", Context.MODE_PRIVATE)
                    .getBoolean("auto_start", false);
            if (auto) {
                ContextCompat.startForegroundService(
                        context,
                        new Intent(context, WakeService.class)
                );
            }
        }
    }
}
