package org.cwmobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

public final class UsbPermissionReceiver extends BroadcastReceiver {
    interface Listener { void permission(UsbDevice device, boolean granted, int token); }
    static final String ACTION = "org.cwmobile.USB_PERMISSION";
    static final String TOKEN = "request_token";
    static Listener listener;

    @Override public void onReceive(Context context, Intent intent) {
        if (ACTION.equals(intent.getAction()) && listener != null) {
            listener.permission(intent.getParcelableExtra(UsbManager.EXTRA_DEVICE),
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false), intent.getIntExtra(TOKEN, -1));
        }
    }
}
