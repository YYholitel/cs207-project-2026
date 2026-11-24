package edu.sustech.cs207.egopuzzle;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Permission prompts and connection attempts have an activity-scoped lifetime. */
final class BoardConnections {
    private final Activity activity;
    private final BiConsumer<SerialTransport, String> connected;
    private final Consumer<String> message;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final String permissionAction;
    private UsbDevice pendingUsb;
    private Closeable pending;
    private int generation;

    BoardConnections(Activity activity, BiConsumer<SerialTransport, String> connected, Consumer<String> message) {
        this.activity = activity;
        this.connected = connected;
        this.message = message;
        permissionAction = activity.getPackageName() + ".USB_PERMISSION";
        IntentFilter filter = new IntentFilter(permissionAction);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(receiver, filter);
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!permissionAction.equals(intent.getAction()) || pendingUsb == null) return;
            UsbDevice requested = pendingUsb;
            pendingUsb = null;
            UsbManager manager = activity.getSystemService(UsbManager.class);
            if (manager.hasPermission(requested)) openUsb(manager, requested);
            else message.accept("USB 权限未授予，请重新连接并允许访问");
        }
    };

    void usb() {
        cancel();
        UsbManager manager = activity.getSystemService(UsbManager.class);
        List<UsbDevice> devices = new ArrayList<>();
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (UsbBoardTransport.supports(device)) devices.add(device);
        }
        if (devices.isEmpty()) {
            message.accept("未找到 EGO1，请开启开发板电源并用 USB OTG 数据线连接手机");
            return;
        }
        String[] labels = devices.stream().map(d -> "EGO1 · " + d.getDeviceName() + " · UART B").toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle("选择 USB 开发板")
                .setItems(labels, (dialog, index) -> {
                    UsbDevice device = devices.get(index);
                    if (manager.hasPermission(device)) openUsb(manager, device);
                    else {
                        pendingUsb = device;
                        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                        Intent intent = new Intent(permissionAction).setPackage(activity.getPackageName());
                        manager.requestPermission(device, PendingIntent.getBroadcast(activity, 0, intent, flags));
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void openUsb(UsbManager manager, UsbDevice device) {
        int attempt = generation;
        message.accept("正在连接 USB UART B…");
        worker.execute(() -> {
            try { deliver(new UsbBoardTransport(manager, device), "USB · UART B", attempt); }
            catch (IOException | SecurityException e) { error(e, attempt); }
        });
    }

    @SuppressLint("MissingPermission")
    void bluetooth() {
        cancel();
        if (Build.VERSION.SDK_INT >= 31 && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 207);
            return;
        }
        BluetoothManager manager = activity.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            message.accept("请先在手机设置里开启蓝牙，并配对串口模块（SPP）");
            return;
        }
        List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
        if (devices.isEmpty()) {
            message.accept("请先在手机蓝牙设置里配对 HC-05 等经典蓝牙串口模块");
            return;
        }
        String[] labels = devices.stream().map(d -> d.getName() + " · " + d.getAddress()).toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle("选择已配对的串口模块")
                .setItems(labels, (dialog, index) -> openBluetooth(devices.get(index)))
                .setNegativeButton("取消", null).show();
    }

    private void openBluetooth(BluetoothDevice device) {
        int attempt = generation;
        try {
            BluetoothSocket socket = BluetoothBoardTransport.socketFor(device);
            pending = socket;
            message.accept("正在连接蓝牙…");
            ui.postDelayed(() -> {
                if (attempt == generation && pending == socket) {
                    cancel();
                    message.accept("蓝牙连接超时，请检查模块供电和串口服务");
                }
            }, 12_000);
            worker.execute(() -> {
                try { deliver(new BluetoothBoardTransport(socket), "蓝牙 · SPP", attempt); }
                catch (IOException | SecurityException e) { error(e, attempt); }
            });
        } catch (IOException | SecurityException e) { error(e, attempt); }
    }

    private void deliver(SerialTransport transport, String label, int attempt) {
        ui.post(() -> {
            if (attempt != generation) {
                try { transport.close(); } catch (IOException ignored) { }
                return;
            }
            pending = null;
            connected.accept(transport, label);
        });
    }

    private void error(Exception error, int attempt) {
        ui.post(() -> {
            if (attempt == generation) {
                pending = null;
                message.accept(error.getMessage() == null ? "连接失败，请重试" : error.getMessage());
            }
        });
    }

    void cancel() {
        generation++;
        pendingUsb = null;
        if (pending != null) {
            try { pending.close(); } catch (IOException ignored) { }
            pending = null;
        }
    }

    void destroy() {
        cancel();
        activity.unregisterReceiver(receiver);
        worker.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }
}
