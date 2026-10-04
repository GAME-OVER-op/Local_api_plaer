package com.tabletplayer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Event-driven battery reports; no Bluetooth discovery, periodic polling, or background service. */
final class HeadsetBatteryMonitor {
    interface Listener { void onBattery(boolean connected, String name, int percentage); }
    private static final String BATTERY_EVENT = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String BATTERY_EXTRA = "android.bluetooth.device.extra.BATTERY_LEVEL";
    private static final String ACTIVE_EVENT = "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED";
    private static final UUID BAS = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> levels = new HashMap<>();
    private BluetoothAdapter adapter;
    private BluetoothA2dp a2dp;
    private BluetoothHeadset headset;
    private BluetoothDevice selected;
    private BluetoothGatt gatt;
    private boolean running;
    private String preferred = "", gattAttempt = "";
    private final Runnable gattTimeout = this::closeGatt;

    HeadsetBatteryMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
    }

    private final BluetoothProfile.ServiceListener profiles = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (!running) { if (adapter != null) adapter.closeProfileProxy(profile, proxy); return; }
            if (profile == BluetoothProfile.A2DP) a2dp = (BluetoothA2dp) proxy;
            if (profile == BluetoothProfile.HEADSET) headset = (BluetoothHeadset) proxy;
            refresh();
        }
        @Override public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) a2dp = null;
            if (profile == BluetoothProfile.HEADSET) headset = null;
            if (running) refresh();
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (!running) return;
            try {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String action = intent.getAction();
                if (ACTIVE_EVENT.equals(action)) preferred = device == null ? "" : device.getAddress();
                if (device != null && (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)
                        || intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_DISCONNECTED))
                    levels.remove(device.getAddress());
                if (device != null && BATTERY_EVENT.equals(action)) put(device, intent.getIntExtra(BATTERY_EXTRA, -1));
                if (device != null && BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT.equals(action)) {
                    Object args = intent.getExtras() == null ? null
                            : intent.getExtras().get(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS);
                    if (args instanceof Object[]) put(device, HeadsetBatteryLevels.vendor(
                            intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD), (Object[]) args));
                }
                refresh();
            } catch (Exception ignoredError) { listener.onBattery(false, "", -1); }
        }
    };

    void start() {
        if (running) return;
        try {
            adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) { listener.onBattery(false, "", -1); return; }
            running = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(BATTERY_EVENT); filter.addAction(ACTIVE_EVENT);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED);
            filter.addAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
            // Category matching is needed for these public HFP vendor broadcasts.
            filter.addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + ".55");
            filter.addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + ".76");
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter);
            adapter.getProfileProxy(context, profiles, BluetoothProfile.A2DP);
            adapter.getProfileProxy(context, profiles, BluetoothProfile.HEADSET);
            refresh();
        } catch (Exception ignored) { stop(); listener.onBattery(false, "", -1); }
    }

    private void put(BluetoothDevice device, int level) {
        int valid = HeadsetBatteryLevels.percent(level);
        if (valid >= 0) levels.put(device.getAddress(), valid);
        else levels.remove(device.getAddress());
    }

    private void refresh() {
        if (!running) return;
        try {
            BluetoothDevice next = null;
            if (adapter.isEnabled() && a2dp != null) {
                List<BluetoothDevice> devices = a2dp.getConnectedDevices();
                for (BluetoothDevice device : devices) {
                    if (a2dp.isA2dpPlaying(device) || device.getAddress().equals(preferred)) { next = device; break; }
                }
                if (next == null && !devices.isEmpty()) next = devices.get(0);
            }
            if (next == null && adapter.isEnabled() && headset != null) {
                List<BluetoothDevice> devices = headset.getConnectedDevices();
                if (!devices.isEmpty()) next = devices.get(0);
            }
            if (selected == null ? next != null : !selected.equals(next)) {
                closeGatt(); gattAttempt = ""; selected = next;
                if (next != null) {
                    // Available on some ROMs; restrictions or absent methods are optional failures.
                    try { put(next, (Integer) BluetoothDevice.class.getMethod("getBatteryLevel").invoke(next)); }
                    catch (Exception ignored) {}
                }
            }
            if (selected == null) { levels.clear(); listener.onBattery(false, "", -1); return; }
            int value = levels.containsKey(selected.getAddress()) ? levels.get(selected.getAddress()) : -1;
            listener.onBattery(true, selected.getName(), value);
            if (value < 0 && !selected.getAddress().equals(gattAttempt)
                    && selected.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                gattAttempt = selected.getAddress();
                gatt = selected.connectGatt(context, false, gattEvents);
                ui.postDelayed(gattTimeout, 8000);
            }
        } catch (Exception ignored) { listener.onBattery(false, "", -1); }
    }

    private final BluetoothGattCallback gattEvents = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt client, int status, int state) {
            ui.post(() -> {
                if (!running || client != gatt) return;
                try {
                    if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                        if (!client.discoverServices()) closeGatt();
                    } else closeGatt();
                } catch (Exception ignored) { closeGatt(); }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt client, int status) {
            ui.post(() -> {
                if (!running || client != gatt) return;
                try {
                    BluetoothGattService service = client.getService(BAS);
                    BluetoothGattCharacteristic level = service == null ? null : service.getCharacteristic(LEVEL);
                    if (status != BluetoothGatt.GATT_SUCCESS || level == null || !client.readCharacteristic(level)) closeGatt();
                } catch (Exception ignored) { closeGatt(); }
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt client, BluetoothGattCharacteristic c, int status) {
            reportGatt(client, c, c.getValue(), status, true);
        }
        @Override public void onCharacteristicRead(BluetoothGatt client, BluetoothGattCharacteristic c, byte[] value, int status) {
            reportGatt(client, c, value, status, true);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt client, BluetoothGattCharacteristic c) {
            reportGatt(client, c, c.getValue(), BluetoothGatt.GATT_SUCCESS, false);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt client, BluetoothGattCharacteristic c, byte[] value) {
            reportGatt(client, c, value, BluetoothGatt.GATT_SUCCESS, false);
        }
    };

    private void reportGatt(BluetoothGatt client, BluetoothGattCharacteristic c, byte[] bytes, int status, boolean subscribe) {
        final byte[] value = bytes == null ? null : bytes.clone();
        ui.post(() -> {
            if (!running || client != gatt || selected == null || !LEVEL.equals(c.getUuid())) return;
            if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.length == 0) { closeGatt(); return; }
            if (HeadsetBatteryLevels.percent(value[0] & 0xff) < 0) { closeGatt(); return; }
            put(selected, value[0] & 0xff); refresh();
            ui.removeCallbacks(gattTimeout);
            if (!subscribe) return;
            try {
                BluetoothGattDescriptor descriptor = c.getDescriptor(CCC);
                boolean notify = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                boolean indicate = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                if (descriptor == null || (!notify && !indicate) || !client.setCharacteristicNotification(c, true)) { closeGatt(); return; }
                descriptor.setValue(notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                if (!client.writeDescriptor(descriptor)) closeGatt();
            } catch (Exception ignored) { closeGatt(); }
        });
    }

    private void closeGatt() {
        ui.removeCallbacks(gattTimeout);
        BluetoothGatt old = gatt; gatt = null;
        if (old != null) {
            try { old.disconnect(); } catch (Exception ignored) {}
            try { old.close(); } catch (Exception ignored) {}
        }
    }

    void stop() {
        running = false;
        try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
        closeGatt();
        if (adapter != null) {
            try {
                if (a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
                if (headset != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset);
            } catch (Exception ignored) {}
        }
        a2dp = null; headset = null; selected = null;
        preferred = gattAttempt = ""; levels.clear();
    }
}
