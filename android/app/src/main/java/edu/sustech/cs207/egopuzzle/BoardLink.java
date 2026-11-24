package edu.sustech.cs207.egopuzzle;

import java.io.Serializable;
import java.util.function.Consumer;

/** Each command returns the board's confirmed state. */
interface BoardLink {
    enum Action { SYNC, RESET, PLACE, MOVE, UNDO, SHOW_STATS, PRACTICE, DEMO_NEXT, VIEW, HIGH_PAGE }
    enum Phase { SETUP, PLAYING, WON, STATS }
    enum Feedback { NONE, BAD_INPUT, INVALID_MOVE, UNDONE }

    void send(Action action, int tile, Consumer<Snapshot> onState);

    final class Snapshot implements Serializable {
        private final int[] tiles;
        final Phase phase;
        final Feedback feedback;
        final int setupIndex;
        final int validMoves;
        final int invalidMoves;
        final int hints;
        final boolean canUndo;
        final int demoStep;
        final boolean demoMode;
        final boolean hintView;
        final boolean highPage;

        Snapshot(int[] tiles, Phase phase, Feedback feedback, int setupIndex,
                 int validMoves, int invalidMoves, int hints, boolean canUndo, int demoStep) {
            this(tiles, phase, feedback, setupIndex, validMoves, invalidMoves, hints, canUndo,
                    demoStep, demoStep > 0, false, false);
        }

        Snapshot(int[] tiles, Phase phase, Feedback feedback, int setupIndex,
                 int validMoves, int invalidMoves, int hints, boolean canUndo, int demoStep,
                 boolean demoMode, boolean hintView, boolean highPage) {
            this.tiles = tiles.clone();
            this.phase = phase;
            this.feedback = feedback;
            this.setupIndex = setupIndex;
            this.validMoves = validMoves;
            this.invalidMoves = invalidMoves;
            this.hints = hints;
            this.canUndo = canUndo;
            this.demoStep = demoStep;
            this.demoMode = demoMode;
            this.hintView = hintView;
            this.highPage = highPage;
        }

        int tileAt(int index) {
            return tiles[index];
        }

        boolean movable(int tile) {
            return tile > 0 && (hints & (1 << (8 - tile))) != 0;
        }
    }
}
