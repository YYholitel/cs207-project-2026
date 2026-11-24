package edu.sustech.cs207.egopuzzle;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** All reads, polling and writes share one queue. Only confirmed snapshots reach the UI. */
final class RemoteBoard implements BoardLink, AutoCloseable {
    private final SerialTransport transport;
    private final BoardProtocol protocol;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final Consumer<Snapshot> states;
    private final Consumer<String> errors;
    private Snapshot last;

    RemoteBoard(SerialTransport transport, Consumer<Snapshot> states, Consumer<String> errors) {
        this.transport = transport;
        protocol = new BoardProtocol(transport);
        this.states = states;
        this.errors = errors;
    }

    synchronized void start() {
        worker.execute(() -> {
            if (closed.get()) return;
            try { protocol.command("a"); } catch (IOException e) { fail(e); }
        });
        worker.scheduleWithFixedDelay(() -> {
            if (closed.get()) return;
            try {
                last = protocol.query();
                states.accept(last);
            } catch (IOException e) {
                fail(e);
            }
        }, 0, 350, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void send(Action action, int tile, Consumer<Snapshot> onState) {
        if (closed.get() || !pending.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                if (closed.get()) return;
                last = protocol.query();
                if (closed.get()) return;
                if (action != Action.SYNC) {
                    protocol.command("a");
                    switch (action) {
                        case RESET -> protocol.command("r");
                        case PLACE, MOVE -> {
                            if (tile < 0 || tile > 15) throw new IOException("棋子编号无效");
                            protocol.command(Integer.toHexString(tile).toUpperCase(java.util.Locale.ROOT));
                        }
                        case UNDO -> protocol.command("u");
                        case SHOW_STATS -> protocol.command("c");
                        case VIEW -> protocol.command("v");
                        case HIGH_PAGE -> protocol.command("h");
                        case PRACTICE -> {
                            protocol.command("r123456708");
                        }
                        case DEMO_NEXT -> {
                            if (!last.demoMode) protocol.command("d");
                            protocol.command("n");
                        }
                        case SYNC -> { }
                    }
                    last = protocol.query();
                }
            } catch (IOException e) {
                fail(e);
            } finally {
                pending.set(false);
            }
            if (!closed.get()) onState.accept(last);
        });
    }

    private void fail(IOException error) {
        if (!closed.get()) {
            close();
            errors.accept(error.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try { protocol.command("z"); } catch (IOException ignored) { }
            try { transport.close(); } catch (IOException ignored) { }
        });
        worker.shutdown();
    }
}
