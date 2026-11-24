package edu.sustech.cs207.egopuzzle;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the installed Activity through the production RemoteBoard and protocol decoder. */
public final class ConnectionTestRunner extends Instrumentation {
    private MainActivity activity;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (MainActivity) startActivitySync(intent);
            waitText("可直接练习，也可以连接开发板");
            click("USB 连接");
            waitText("未找到 EGO1，请开启开发板电源并用 USB OTG 数据线连接手机");
            click("模拟板");
            click("练习关");
            click("1");
            waitText("无法移动 · 棋子必须紧邻空格");
            click("撤回");
            waitText("已撤回上一步");
            click("8");
            waitText("已通关");
            click("查看统计");
            waitText("通关统计 · 有效 1 步，无效 0 步");
            click("复位");
            for (char tile : "123456708".toCharArray()) click(String.valueOf(tile));
            waitText("点击与空格相邻的棋子");
            click("开始演示");
            for (int step = 1; step <= 4; step++) click("演示下一步 · " + step + "/5");
            waitText("演示已复位 · 点击下一步重播");
            click("演示下一步 · 5/5");
            waitText("点击与空格相邻的棋子");

            FakeWire wire = new FakeWire();
            runOnMainSync(() -> {
                try {
                    Method attach = MainActivity.class.getDeclaredMethod("useRemote", SerialTransport.class, String.class);
                    attach.setAccessible(true);
                    attach.invoke(activity, wire, "协议测试");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            waitText("已连接 · 棋盘由开发板确认");
            click("练习关");
            waitText("点击与空格相邻的棋子");
            click("1");
            waitText("无法移动 · 棋子必须紧邻空格");
            click("8");
            waitText("已通关");
            click("查看统计");
            waitText("通关统计 · 有效 1 步，无效 1 步");
            // A physical board action changes the transport state without a phone click.
            wire.externalReset();
            waitText("设置第 1 / 9 格 · 0 为空格");
            wire.silent = true;
            waitContains("连接已中断");
            AtomicBoolean disabled = new AtomicBoolean();
            runOnMainSync(() -> disabled.set(!find(activity.getWindow().getDecorView(), "练习关").isEnabled()));
            if (!disabled.get()) throw new AssertionError("Controls active after disconnect");
            click("模拟板");
            click("练习关");
            waitText("点击与空格相邻的棋子");
            result.putString("stream", "PASS: installed UI, USB absent, simulator, UART frames, remote move/win/stats, external reset, timeout, disabled controls and recovery\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }

    private void click(String text) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            View view = findClickable(activity.getWindow().getDecorView(), text);
            if (view == null || !view.isEnabled() || !view.performClick()) failure.set(new AssertionError("Cannot click " + text));
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        waitForIdleSync();
    }

    private View findClickable(View view, String text) {
        if (view instanceof TextView label && label.getText().toString().equals(text) && view.isClickable()) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findClickable(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void waitText(String text) throws InterruptedException { waitFor(text, false); }
    private void waitContains(String text) throws InterruptedException { waitFor(text, true); }

    private void waitFor(String text, boolean contains) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            AtomicBoolean found = new AtomicBoolean();
            runOnMainSync(() -> found.set(contains ? hasText(activity.getWindow().getDecorView(), text)
                    : find(activity.getWindow().getDecorView(), text) != null));
            if (found.get()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Missing UI text: " + text);
    }

    private View find(View view, String text) {
        if (view instanceof TextView label && label.getText().toString().equals(text)) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean hasText(View view, String text) {
        if (view instanceof TextView label && label.getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) if (hasText(group.getChildAt(i), text)) return true;
        }
        return false;
    }

    private static final class FakeWire implements SerialTransport {
        private final SimulatedBoard model = new SimulatedBoard();
        private final ArrayDeque<Byte> bytes = new ArrayDeque<>();
        volatile boolean silent;
        private boolean closed;

        synchronized void externalReset() { model.send(BoardLink.Action.RESET, 0, state -> { }); }

        @Override public synchronized void write(byte[] input) throws IOException {
            if (closed) throw new IOException("closed");
            for (byte value : input) {
                char c = (char) value;
                if (c == '?' && !silent) model.send(BoardLink.Action.SYNC, 0, this::reply);
                else if (c == 'r') externalReset();
                else if (c >= '0' && c <= '8') model.send(BoardLink.Action.SYNC, 0, state ->
                    model.send(state.phase == BoardLink.Phase.SETUP ? BoardLink.Action.PLACE : BoardLink.Action.MOVE,
                            c - '0', confirmed -> { }));
                else if (c == 'c') model.send(BoardLink.Action.SHOW_STATS, 0, state -> { });
                else if (c == 'u') model.send(BoardLink.Action.UNDO, 0, state -> { });
            }
        }

        private void reply(BoardLink.Snapshot state) {
            byte[] packet = new byte[28];
            packet[0] = (byte) 0xA5;
            packet[1] = 0x5A;
            packet[2] = 1;
            packet[3] = (byte) (32 | (state.canUndo ? 16 : 0) | (state.phase == BoardLink.Phase.STATS ? 4 : 0)
                    | (state.feedback == BoardLink.Feedback.INVALID_MOVE || state.feedback == BoardLink.Feedback.BAD_INPUT ? 2 : 0));
            packet[4] = (byte) (state.phase == BoardLink.Phase.SETUP ? 0 : state.phase == BoardLink.Phase.PLAYING ? 1 : 2);
            packet[5] = (byte) state.setupIndex;
            packet[6] = (byte) state.validMoves;
            packet[7] = (byte) state.invalidMoves;
            packet[8] = (byte) state.hints;
            for (int i = 0; i < 9; i++) packet[10 + i] = (byte) (state.tileAt(i) < 0 ? 15 : state.tileAt(i));
            for (int i = 2; i < 27; i++) packet[27] ^= packet[i];
            bytes.add((byte) 0); // Noise before the valid, fragmented reply.
            for (byte b : packet) bytes.add(b);
        }

        @Override public synchronized int read(byte[] target, int timeout) throws IOException {
            if (closed) throw new IOException("closed");
            int count = Math.min(3, bytes.size());
            for (int i = 0; i < count; i++) target[i] = bytes.remove();
            return count;
        }

        @Override public synchronized void close() { closed = true; }
    }
}
