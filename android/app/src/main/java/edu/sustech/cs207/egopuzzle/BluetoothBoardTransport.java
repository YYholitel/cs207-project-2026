package edu.sustech.cs207.egopuzzle;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

final class BluetoothBoardTransport implements SerialTransport {
    private final BluetoothSocket socket;
    private final InputStream input;
    private final OutputStream output;
    private volatile boolean closed;

    @SuppressLint("MissingPermission")
    static BluetoothSocket socketFor(BluetoothDevice device) throws IOException {
        return device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
    }

    @SuppressLint("MissingPermission")
    BluetoothBoardTransport(BluetoothSocket socket) throws IOException {
        this.socket = socket;
        try {
            socket.connect();
            input = socket.getInputStream();
            output = socket.getOutputStream();
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (closed) throw new IOException("蓝牙已断开");
        output.write(bytes);
        output.flush();
    }

    @Override
    public int read(byte[] bytes, int timeoutMs) throws IOException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (closed || !socket.isConnected()) throw new IOException("蓝牙已断开");
            int available = input.available();
            if (available > 0) {
                int count = input.read(bytes, 0, Math.min(bytes.length, available));
                if (count < 0) throw new IOException("蓝牙连接已结束");
                return count;
            }
            try { Thread.sleep(5); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("操作已取消", e);
            }
        }
        return 0;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        socket.close();
    }
}
