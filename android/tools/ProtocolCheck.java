package edu.sustech.cs207.egopuzzle;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class ProtocolCheck {
    static final class FakeTransport implements SerialTransport {
        final ArrayDeque<Byte> bytes = new ArrayDeque<>();
        final StringBuilder commands = new StringBuilder();
        byte[] response = frame("123456708", 1, 0, 0, 32, 0);
        boolean silent;
        volatile boolean closed;
        int fragmentSize = 3;

        @Override public synchronized void write(byte[] value) throws IOException {
            if (closed) throw new IOException("closed");
            for (byte command : value) {
                commands.append((char) command);
                if (command == '?' && !silent) {
                    bytes.add((byte) 0xA5); // Noise and false header before the frame.
                    bytes.add((byte) 0);
                    byte[] corrupt = response.clone();
                    corrupt[27] ^= 1;
                    for (byte b : corrupt) bytes.add(b);
                    for (byte b : response) bytes.add(b);
                }
            }
        }

        @Override public synchronized int read(byte[] target, int timeout) throws IOException {
            if (closed) throw new IOException("closed");
            int count = Math.min(fragmentSize, Math.min(bytes.size(), target.length));
            for (int i = 0; i < count; i++) target[i] = bytes.remove();
            return count;
        }

        @Override public synchronized void close() { closed = true; }
    }

    static byte[] frame(String layout, int phase, int good, int bad, int flags, int demo) {
        byte[] packet = new byte[28];
        packet[0] = (byte) 0xA5;
        packet[1] = 0x5A;
        packet[2] = 1;
        packet[3] = (byte) flags;
        packet[4] = (byte) phase;
        packet[5] = 9;
        packet[6] = (byte) good;
        packet[7] = (byte) bad;
        packet[8] = 11;
        packet[9] = (byte) demo;
        for (int i = 0; i < 9; i++) packet[10 + i] = (byte) (layout.charAt(i) - '0');
        return checksum(packet);
    }

    static byte[] checksum(byte[] packet) {
        packet[27] = 0;
        for (int i = 2; i < 27; i++) packet[27] ^= packet[i];
        return packet;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        byte[] example = frame("123456780", 2, 99, 17, 4 | 16 | 32 | 64, 0);
        BoardLink.Snapshot state = BoardProtocol.decode(example);
        require(state != null && state.phase == BoardLink.Phase.STATS && state.validMoves == 99
                && state.invalidMoves == 17 && state.highPage && state.canUndo, "state fields");
        byte[] bad = example.clone();
        bad[2] = 2;
        require(BoardProtocol.decode(checksum(bad)) == null, "unknown version");
        bad = example.clone();
        bad[11] = bad[10];
        require(BoardProtocol.decode(checksum(bad)) == null, "duplicate tiles");
        FakeTransport fake = new FakeTransport();
        state = new BoardProtocol(fake).query();
        require(state.phase == BoardLink.Phase.PLAYING && state.tileAt(7) == 0, "fragment + noise + checksum resync");
        fake.silent = true;
        long start = System.nanoTime();
        try {
            new BoardProtocol(fake).query();
            throw new AssertionError("missing timeout");
        } catch (IOException expected) {
            require(System.nanoTime() - start < 2_000_000_000L, "bounded query timeout");
        }
        fake = new FakeTransport();
        CountDownLatch initial = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        RemoteBoard remote = new RemoteBoard(fake, s -> initial.countDown(), error::set);
        remote.start();
        require(initial.await(3, TimeUnit.SECONDS), "initial poll");
        CountDownLatch action = new CountDownLatch(1);
        remote.send(BoardLink.Action.PRACTICE, 0, s -> action.countDown());
        require(action.await(3, TimeUnit.SECONDS), "practice callback");
        require(fake.commands.toString().contains("ar123456708?"), "enable, reset, layout, query order");
        for (BoardLink.Action next : new BoardLink.Action[]{BoardLink.Action.VIEW, BoardLink.Action.HIGH_PAGE,
                BoardLink.Action.UNDO, BoardLink.Action.SHOW_STATS, BoardLink.Action.DEMO_NEXT}) {
            CountDownLatch replied = new CountDownLatch(1);
            remote.send(next, 0, s -> replied.countDown());
            require(replied.await(3, TimeUnit.SECONDS), "command callback " + next);
        }
        String commands = fake.commands.toString();
        require(commands.contains("av?") && commands.contains("ah?") && commands.contains("au?")
                && commands.contains("ac?") && commands.contains("adn?"), "view, page, undo, stats and demo mapping");
        remote.close();
        for (int i = 0; i < 100 && !fake.closed; i++) Thread.sleep(10);
        require(fake.closed && fake.commands.toString().endsWith("z") && error.get() == null, "release UART control and close");
        System.out.println("PASS: protocol fields, 0-99 counters, corruption, fragmentation, timeout, worker ordering and disconnect");
        if (args.length > 0) {
            int frames = 0;
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[0]))) {
                if (!line.startsWith("ANDROID_FRAME ")) continue;
                String hex = line.substring("ANDROID_FRAME ".length()).trim();
                byte[] packet = java.util.HexFormat.of().parseHex(hex);
                require(BoardProtocol.decode(packet) != null, "RTL UART frame rejected: " + hex);
                frames++;
            }
            require(frames >= 20, "missing RTL telemetry coverage");
            System.out.println("PASS: decoded " + frames + " UART snapshots emitted by actual FPGA RTL");
        }
    }
}
