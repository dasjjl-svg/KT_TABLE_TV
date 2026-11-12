package net.quber.afactorymode;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

final class ReconnectScheduler {
    private final Executor executor;
    private final Runnable task;
    private boolean connected, queued, closed;

    ReconnectScheduler(Executor executor, Runnable task) {
        this.executor = executor;
        this.task = task;
    }

    synchronized void connectionChanged(boolean available) {
        boolean restored = available && !connected;
        connected = available;
        if (closed || !restored || queued) return;
        queued = true;
        try {
            executor.execute(new Runnable() {
                @Override public void run() {
                    synchronized (ReconnectScheduler.this) {
                        queued = false;
                        if (closed || !connected) return;
                    }
                    task.run();
                }
            });
        } catch (RejectedExecutionException stopped) {
            queued = false;
        }
    }

    synchronized boolean isClosed() { return closed; }
    synchronized void close() { closed = true; }
}
