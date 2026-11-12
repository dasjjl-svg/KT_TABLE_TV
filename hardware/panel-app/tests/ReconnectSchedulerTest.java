package net.quber.afactorymode;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class ReconnectSchedulerTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();
        boolean stopped;
        @Override public void execute(Runnable task) {
            if (stopped) throw new RejectedExecutionException();
            tasks.add(task);
        }
        void next() { tasks.remove().run(); }
    }

    public static void main(String[] args) {
        Queue queue = new Queue();
        final int[] calls = {0};
        ReconnectScheduler scheduler = new ReconnectScheduler(queue, new Runnable() {
            @Override public void run() { calls[0]++; }
        });
        scheduler.connectionChanged(false);
        check(queue.tasks.isEmpty(), "offline boot must not start network work");
        scheduler.connectionChanged(true);
        for (int i = 0; i < 100; i++) scheduler.connectionChanged(true);
        check(queue.tasks.size() == 1, "duplicate connectivity broadcasts must coalesce");
        queue.next();
        check(calls[0] == 1, "first available network triggers recovery immediately");
        scheduler.connectionChanged(true);
        check(queue.tasks.isEmpty(), "connected broadcasts must not bypass normal polling");
        scheduler.connectionChanged(false);
        scheduler.connectionChanged(true);
        scheduler.connectionChanged(false);
        scheduler.connectionChanged(true);
        check(queue.tasks.size() == 1, "network flaps before dispatch must not queue duplicate work");
        queue.next();
        check(calls[0] == 2, "a restored connection must retry after earlier work completed");
        scheduler.connectionChanged(false);
        scheduler.connectionChanged(true);
        scheduler.connectionChanged(false);
        queue.next();
        check(calls[0] == 2, "connection lost before dispatch must not run network work");
        scheduler.connectionChanged(true);
        scheduler.close();
        queue.next();
        check(calls[0] == 2 && scheduler.isClosed(), "destroyed service must discard queued callbacks");
        scheduler.connectionChanged(false);
        scheduler.connectionChanged(true);
        check(queue.tasks.isEmpty(), "late connectivity broadcasts must not restart a destroyed service");

        Queue rejectedQueue = new Queue();
        rejectedQueue.stopped = true;
        ReconnectScheduler rejected = new ReconnectScheduler(rejectedQueue, new Runnable() {
            @Override public void run() { calls[0]++; }
        });
        rejected.connectionChanged(true);
        check(rejectedQueue.tasks.isEmpty(), "executor shutdown race must not throw from broadcast receiver");
        rejectedQueue.stopped = false;
        rejected.connectionChanged(false);
        rejected.connectionChanged(true);
        rejectedQueue.next();
        check(calls[0] == 3, "rejected dispatch must not leave the queue permanently busy");
        System.out.println("Connectivity recovery checks passed: " + checks);
    }
}
