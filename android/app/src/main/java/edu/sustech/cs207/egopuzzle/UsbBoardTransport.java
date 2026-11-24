package edu.sustech.cs207.egopuzzle;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import java.io.IOException;

/** EGO1 FT2232H channel B. Channel A belongs to JTAG and is never claimed. */
final class UsbBoardTransport implements SerialTransport {
    private final UsbDeviceConnection connection;
    private final UsbInterface uart;
    private final UsbEndpoint input;
    private final UsbEndpoint output;
    private volatile boolean closed;

    static boolean supports(UsbDevice device) {
        return device.getVendorId() == 0x0403 && device.getProductId() == 0x6010
                && device.getInterfaceCount() >= 2;
    }

    UsbBoardTransport(UsbManager manager, UsbDevice device) throws IOException {
        if (!supports(device)) throw new IOException("请连接 EGO1 的 FT2232 双通道 USB 接口");
        UsbInterface selected = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getId() == 1) selected = device.getInterface(i);
        }
        if (selected == null) throw new IOException("找不到 UART B 通道");
        uart = selected;
        UsbEndpoint rx = null;
        UsbEndpoint tx = null;
        for (int i = 0; i < uart.getEndpointCount(); i++) {
            UsbEndpoint endpoint = uart.getEndpoint(i);
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) rx = endpoint;
            else tx = endpoint;
        }
        if (rx == null || tx == null) throw new IOException("USB 串口端点不完整");
        input = rx;
        output = tx;
        connection = manager.openDevice(device);
        if (connection == null) throw new IOException("无法打开 USB，请授予设备访问权限");
        try {
            if (!connection.claimInterface(uart, true)) throw new IOException("UART B 通道已被占用");
            control(0, 0);       // Reset UART B only.
            control(3, 26);      // 3 MHz / 26 = 115384.6 baud, 0.16% error at 115200.
            control(4, 8);       // 8 data bits, no parity, one stop bit.
            control(2, 0);       // No flow control.
            control(9, 1);       // One millisecond latency timer.
            control(0, 1);       // Purge RX.
            control(0, 2);       // Purge TX.
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private void control(int request, int value) throws IOException {
        if (connection.controlTransfer(0x40, request, value, uart.getId() + 1,
                null, 0, 1000) < 0) throw new IOException("配置 USB 串口失败");
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (closed || connection.bulkTransfer(output, bytes, bytes.length, 1000) != bytes.length)
            throw new IOException("USB 已断开或发送失败");
    }

    @Override
    public int read(byte[] bytes, int timeoutMs) throws IOException {
        if (closed) throw new IOException("USB 已断开");
        // One USB packet per read, so the first two FTDI modem status bytes are unambiguous.
        byte[] packet = new byte[input.getMaxPacketSize()];
        int count = connection.bulkTransfer(input, packet, packet.length, timeoutMs);
        if (count <= 2) return 0;
        int payload = count - 2;
        if (payload > bytes.length) throw new IOException("USB 接收缓冲不足");
        System.arraycopy(packet, 2, bytes, 0, payload);
        return payload;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        connection.releaseInterface(uart);
        connection.close();
    }
}
