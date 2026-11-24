package edu.sustech.cs207.egopuzzle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Version 1 UART protocol from rtl/puzzle_top.v, 115200 8N1. */
final class BoardProtocol {
    private final SerialTransport transport;

    BoardProtocol(SerialTransport transport) {
        this.transport = transport;
    }

    void command(String command) throws IOException {
        for (char c : command.toCharArray()) {
            transport.write(String.valueOf(c).getBytes(StandardCharsets.US_ASCII));
            try {
                Thread.sleep(4);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("操作已取消", e);
            }
        }
    }

    BoardLink.Snapshot query() throws IOException {
        // Consume a late response before issuing the next query. Writes are never retried.
        byte[] input = new byte[512];
        long drainDeadline = System.nanoTime() + 100_000_000L;
        while (transport.read(input, 10) > 0 && System.nanoTime() < drainDeadline) { }
        command("?");
        byte[] frame = new byte[28];
        int used = 0;
        long deadline = System.nanoTime() + 1_500_000_000L;
        while (System.nanoTime() < deadline) {
            int count = transport.read(input, 100);
            for (int i = 0; i < count; i++) {
                frame[used++] = input[i];
                if (used == frame.length) {
                    BoardLink.Snapshot state = decode(frame);
                    if (state != null) return state;
                    System.arraycopy(frame, 1, frame, 0, frame.length - 1);
                    used--;
                }
            }
        }
        throw new IOException("未收到有效棋盘状态，请检查供电、UART 和 115200 波特率");
    }

    static BoardLink.Snapshot decode(byte[] frame) {
        if (frame.length != 28 || u(frame[0]) != 0xA5 || u(frame[1]) != 0x5A || frame[2] != 1) return null;
        int checksum = 0;
        for (int i = 2; i < 27; i++) checksum ^= u(frame[i]);
        if (checksum != u(frame[27]) || frame[25] != 0 || frame[26] != 0) return null;
        int flags = u(frame[3]);
        int phase = u(frame[4]);
        int entered = u(frame[5]);
        if ((flags & 128) != 0 || phase > 2 || entered > 9 || u(frame[6]) > 99
                || u(frame[7]) > 99 || u(frame[9]) > 5 || (phase != 0 && entered != 9)) return null;
        int[] tiles = new int[9];
        boolean[] seen = new boolean[9];
        for (int i = 0; i < tiles.length; i++) {
            int value = u(frame[10 + i]);
            if (i < entered) {
                if (value > 8 || seen[value]) return null;
                seen[value] = true;
                tiles[i] = value;
            } else {
                if (value != 15) return null;
                tiles[i] = -1;
            }
        }
        BoardLink.Phase state = phase == 0 ? BoardLink.Phase.SETUP
                : phase == 1 ? BoardLink.Phase.PLAYING
                : (flags & 4) != 0 ? BoardLink.Phase.STATS : BoardLink.Phase.WON;
        BoardLink.Feedback error = (flags & 2) == 0 ? BoardLink.Feedback.NONE
                : phase == 0 ? BoardLink.Feedback.BAD_INPUT : BoardLink.Feedback.INVALID_MOVE;
        return new BoardLink.Snapshot(tiles, state, error, entered, u(frame[6]), u(frame[7]),
                u(frame[8]), (flags & 16) != 0, u(frame[9]), (flags & 1) != 0,
                (flags & 8) != 0, (flags & 64) != 0);
    }

    private static int u(byte value) {
        return value & 255;
    }
}
