package net.quber.afactorymode;

import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AdbNetworkSetup {
    private static final String DESCRIPTOR = "android.os.HiSysManagerService";
    private static final String PORT_PROPERTY = "service.adb.tcp.port";
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final AtomicBoolean BINDER_IN_FLIGHT = new AtomicBoolean();
    private static final int MAX_RESULT = 2000;
    private static final long CALL_TIMEOUT_MS = 5000;
    private static final long POLL_MS = 8000;

    private AdbNetworkSetup() {}

    public static String run(final Context context) {
        if (Looper.myLooper() == Looper.getMainLooper()) return "result=blocked_main_thread\n";
        if (!RUNNING.compareAndSet(false, true)) return "result=blocked_trial_in_progress\n";
        StringBuilder result = new StringBuilder();
        String phase = "preflight";
        try {
            if (BINDER_IN_FLIGHT.get()) return "result=blocked_binder_still_in_flight\n";
            if (context == null || !"CT1105_SB".equals(Build.MODEL)) return "result=blocked_device\n";
            final Method get = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            if (!secure(context, get)) return "result=blocked_security_preconditions\n";
            if (!reviewedService()) return "result=blocked_service_build_mismatch\n";
            String originalPort = property(get, PORT_PROPERTY);
            String originalDaemon = property(get, "init.svc.adbd");
            add(result, "before.port=" + portLabel(originalPort));
            add(result, "before.daemon=" + daemonLabel(originalDaemon));
            if (!AdbNetworkProtocol.allowedPort(originalPort)) return finish(result, "blocked_existing_port");
            if (!stableDaemon(originalDaemon)) return finish(result, "blocked_daemon_state");
            if ("running".equals(originalDaemon) && !"5555".equals(originalPort)) {
                return finish(result, "skipped_running_daemon_requires_restart");
            }
            boolean alreadyOpen = tcpOpen(300);
            if (alreadyOpen) {
                if ("running".equals(originalDaemon) && "5555".equals(originalPort)) {
                    add(result, "adb.authentication=not_tested");
                    return finish(result, "existing_tcp_listener_adb_unverified");
                }
                return finish(result, "blocked_existing_tcp_listener");
            }
            phase = "binder_lookup";
            final IBinder binder = findService();
            if (binder == null) return finish(result, "blocked_service_unavailable");
            if (!secure(context, get) || !originalPort.equals(property(get, PORT_PROPERTY))
                    || !originalDaemon.equals(property(get, "init.svc.adbd"))) {
                return finish(result, "blocked_preconditions_changed");
            }
            boolean changedPort = !"5555".equals(originalPort);
            if (changedPort) {
                phase = "set_port";
                int setPortStatus = transact(binder, AdbNetworkProtocol.setPort("5555"));
                add(result, "set_port.raw_status=" + setPortStatus);
                String observedPort = property(get, PORT_PROPERTY);
                add(result, "set_port.readback=" + portLabel(observedPort));
                if (!"5555".equals(observedPort)) {
                    return finish(result, originalPort.equals(observedPort)
                            ? "failed_port_unchanged" : "indeterminate_port_changed_externally");
                }
                if (setPortStatus != 0) return finish(result, "indeterminate_set_port_status_no_further_calls");
            }
            if (!"running".equals(originalDaemon)) {
                if (!secure(context, get) || !"5555".equals(property(get, PORT_PROPERTY))
                        || !stableDaemon(property(get, "init.svc.adbd"))) {
                    return finish(result, "indeterminate_start_preconditions_changed");
                }
                phase = "start_daemon";
                add(result, "start_daemon.raw_status=" + transact(binder, AdbNetworkProtocol.startDaemon()));
            }
            phase = "observe";
            long deadline = SystemClock.elapsedRealtime() + POLL_MS;
            String observedPort = "";
            String observedDaemon = "";
            boolean open = false;
            do {
                observedPort = property(get, PORT_PROPERTY);
                observedDaemon = property(get, "init.svc.adbd");
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                open = tcpOpen((int) Math.min(300, remaining));
                if (open && "5555".equals(observedPort) && "running".equals(observedDaemon)) {
                    add(result, "after.port=5555");
                    add(result, "after.daemon=running");
                    add(result, "loopback.tcp5555=open");
                    add(result, "adb.authentication=not_tested");
                    return finish(result, secure(context, get)
                            ? "tcp_listener_ready_adb_unverified" : "indeterminate_security_changed");
                }
                remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining > 0) Thread.sleep(Math.min(250, remaining));
            } while (SystemClock.elapsedRealtime() < deadline);
            add(result, "after.port=" + portLabel(observedPort));
            add(result, "after.daemon=" + daemonLabel(observedDaemon));
            add(result, "loopback.tcp5555=" + (open ? "open" : "closed"));
            if (!open && ("stopped".equals(observedDaemon) || observedDaemon.length() == 0)) {
                if (changedPort && "5555".equals(observedPort)) {
                    String daemonBeforeRestore = property(get, "init.svc.adbd");
                    if (!secure(context, get) || !"5555".equals(property(get, PORT_PROPERTY))
                            || !("stopped".equals(daemonBeforeRestore) || daemonBeforeRestore.length() == 0)
                            || tcpOpen(300)) {
                        return finish(result, "indeterminate_rollback_preconditions_changed");
                    }
                    phase = "restore_port";
                    add(result, "restore_port.raw_status=" + transact(binder, AdbNetworkProtocol.setPort(originalPort)));
                    boolean restored = originalPort.equals(property(get, PORT_PROPERTY));
                    add(result, "restore_port.verified=" + restored);
                    return finish(result, restored ? "failed_no_daemon_port_restored" : "failed_no_daemon_restore_unverified");
                }
                if (!originalPort.equals(observedPort)) return finish(result, "indeterminate_port_changed_externally");
                return finish(result, "failed_no_daemon_no_restore_needed");
            }
            return finish(result, "indeterminate_daemon_or_listener_no_rollback");
        } catch (TimeoutException error) {
            add(result, "phase=" + phase);
            add(result, "binder.call_may_still_complete=true");
            return finish(result, "indeterminate_binder_timeout_no_further_calls");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            add(result, "phase=" + phase);
            add(result, "binder.in_flight=" + BINDER_IN_FLIGHT.get());
            return finish(result, "indeterminate_interrupted_no_further_calls");
        } catch (Exception error) {
            add(result, "phase=" + phase);
            add(result, "error=" + errorName(error));
            return finish(result, "indeterminate_error_no_further_calls");
        } catch (LinkageError error) {
            add(result, "phase=" + phase);
            add(result, "error=" + errorName(error));
            return finish(result, "blocked_platform_unavailable");
        } finally {
            RUNNING.set(false);
        }
    }

    public static String inspectRuntime(final Context context) {
        if (Looper.myLooper() == Looper.getMainLooper()) return "result=blocked_main_thread\n";
        if (!RUNNING.compareAndSet(false, true)) return "result=blocked_trial_in_progress\n";
        try {
            if (BINDER_IN_FLIGHT.get()) return "result=blocked_binder_still_in_flight\n";
            if (context == null || !"CT1105_SB".equals(Build.MODEL)) return "result=blocked_device\n";
            final Method get = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            if (!secure(context, get)) return "result=blocked_security_preconditions\n";
            if (!reviewedService()) return "result=blocked_service_build_mismatch\n";
            final IBinder binder = findService();
            if (binder == null) return "result=blocked_service_unavailable\n";
            return ServiceInspectionRunner.run(new ServiceInspectionRunner.Channel() {
                public String read() throws Exception {
                    return property(get, ServiceInspectionProtocol.KEY);
                }
                public int write(byte[] request) throws Exception {
                    if (!secure(context, get)) throw new IllegalStateException("security_preconditions_changed");
                    return transact(binder, request);
                }
            });
        } catch (TimeoutException error) {
            return "binder.call_may_still_complete=true\nresult=indeterminate_lookup_timeout_no_further_calls\n";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "result=indeterminate_interrupted_no_further_calls\n";
        } catch (Exception error) {
            return "error=" + errorName(error) + "\nresult=inspection_unavailable\n";
        } catch (LinkageError error) {
            return "error=" + errorName(error) + "\nresult=blocked_platform_unavailable\n";
        } finally {
            RUNNING.set(false);
        }
    }

    private static IBinder findService() throws Exception {
        return bounded(new Callable<IBinder>() {
            public IBinder call() throws Exception {
                Method check = Class.forName("android.os.ServiceManager").getMethod("checkService", String.class);
                Object service = check.invoke(null, "HiSysManager");
                if (!(service instanceof IBinder)) return null;
                IBinder candidate = (IBinder) service;
                if (!DESCRIPTOR.equals(candidate.getInterfaceDescriptor())) throw new IllegalStateException("descriptor_mismatch");
                return candidate;
            }
        });
    }

    static BackupAccess beginBackup(final Context context) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("main_thread");
        if (!RUNNING.compareAndSet(false, true)) throw new IllegalStateException("operation_in_progress");
        boolean ready = false;
        try {
            if (BINDER_IN_FLIGHT.get()) throw new IllegalStateException("binder_still_in_flight");
            if (context == null || !"CT1105_SB".equals(Build.MODEL)) throw new IllegalStateException("device_mismatch");
            Method get = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            if (!secure(context, get) || !reviewedService()) throw new IllegalStateException("backup_preconditions");
            IBinder binder = findService();
            if (binder == null) throw new IllegalStateException("service_unavailable");
            BackupAccess access = new BackupAccess(context, get, binder);
            ready = true;
            return access;
        } finally {
            if (!ready) RUNNING.set(false);
        }
    }

    static final class BackupAccess implements AutoCloseable {
        private boolean closed;
        private final SystemBackupSource.Channel channel;

        private BackupAccess(final Context context, final Method get, final IBinder binder) {
            channel = new SystemBackupSource.Channel() {
                public int transact(byte[] request) throws Exception {
                    if (!secure(context, get)) throw new IllegalStateException("security_preconditions_changed");
                    return rawTransact(binder, request);
                }
                public String property(String key) throws Exception {
                    if (!SystemBackupProtocol.KEY.equals(key)) throw new IllegalArgumentException("unexpected_property");
                    return AdbNetworkSetup.property(get, key);
                }
            };
        }

        SystemBackupSource.Result read(final SystemBackupProtocol.Query query) throws Exception {
            if (closed) throw new IllegalStateException("backup_closed");
            return bounded(new Callable<SystemBackupSource.Result>() {
                public SystemBackupSource.Result call() throws Exception { return SystemBackupSource.read(channel, query); }
            });
        }

        SystemBackupSource.Result readBlock(final String name, final long offset, final int length) throws Exception {
            if (closed) throw new IllegalStateException("backup_closed");
            return bounded(new Callable<SystemBackupSource.Result>() {
                public SystemBackupSource.Result call() throws Exception { return SystemBackupSource.readBlock(channel, name, offset, length); }
            });
        }

        SystemBackupSource.Result readBootMedia(final String apk) throws Exception {
            if (closed) throw new IllegalStateException("backup_closed");
            return bounded(new Callable<SystemBackupSource.Result>() {
                public SystemBackupSource.Result call() throws Exception {
                    return SystemBackupSource.readBootMedia(channel, apk);
                }
            }, 15000L);
        }

        SystemBackupSource.Result readSpiController(final String apk) throws Exception {
            if (closed) throw new IllegalStateException("backup_closed");
            return bounded(new Callable<SystemBackupSource.Result>() {
                public SystemBackupSource.Result call() throws Exception {
                    return SystemBackupSource.readSpiController(channel, apk);
                }
            }, 15000L);
        }

        SystemBackupSource.Result readSpiIdentity(final String apk) throws Exception {
            if (closed) throw new IllegalStateException("backup_closed");
            return bounded(new Callable<SystemBackupSource.Result>() {
                public SystemBackupSource.Result call() throws Exception {
                    return SystemBackupSource.readSpiIdentity(channel, apk);
                }
            }, 15000L);
        }

        SystemBackupSource.Result readSpiBackup(final String apk) throws Exception {
            if (closed) throw new IllegalStateException("backup_closed");
            return bounded(new Callable<SystemBackupSource.Result>() {
                public SystemBackupSource.Result call() throws Exception {
                    return SystemBackupSource.readSpiBackup(channel, apk);
                }
            }, 630000L);
        }

        SystemBackupSource.Result readSpiSamples(final String apk) throws Exception {
            if (closed) throw new IllegalStateException("backup_closed");
            return bounded(new Callable<SystemBackupSource.Result>() {
                public SystemBackupSource.Result call() throws Exception {
                    return SystemBackupSource.readSpiSamples(channel, apk);
                }
            }, 45000L);
        }

        public void close() {
            if (!closed) { closed = true; RUNNING.set(false); }
        }

        SystemBackupSource.Result maintenance(final String apk, final SystemBackupProtocol.Helper helper) throws Exception {
            if (closed) throw new IllegalStateException("backup_closed");
            if (SystemBackupProtocol.maintenanceMode(helper) == null) throw new IllegalArgumentException("maintenance_mode");
            return bounded(new Callable<SystemBackupSource.Result>() {
                public SystemBackupSource.Result call() throws Exception {
                    return SystemBackupSource.maintenance(channel, apk, helper);
                }
            }, 45000L);
        }
    }

    private static boolean secure(Context context, Method get) throws Exception {
        return Settings.Global.getInt(context.getContentResolver(), "adb_enabled", 0) == 1
                && "1".equals(property(get, "ro.adb.secure")) && "1".equals(property(get, "ro.secure"));
    }

    private static boolean reviewedService() throws Exception {
        String path = "/system/lib/libhisysmanagerservice.so";
        StructStat before = Os.lstat(path);
        if (!OsConstants.S_ISREG(before.st_mode) || before.st_size != 111864) return false;
        long deadline = SystemClock.elapsedRealtime() + 2000;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileDescriptor descriptor = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
        try (FileInputStream input = new FileInputStream(descriptor)) {
            if (!sameService(before, Os.fstat(descriptor))) return false;
            byte[] buffer = new byte[8192];
            long remaining = before.st_size;
            while (remaining > 0) {
                if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline) return false;
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count <= 0) return false;
                digest.update(buffer, 0, count);
                remaining -= count;
            }
            if (input.read() != -1 || !sameService(before, Os.fstat(descriptor)) || !sameService(before, Os.lstat(path))) return false;
        }
        String expected = "f8af347fbc807748b185d9fa1ce74e3bd04ab0a85b4171594f1b2a45df9f263b";
        byte[] actual = digest.digest();
        for (int i = 0; i < actual.length; i++) {
            if ((actual[i] & 255) != Integer.parseInt(expected.substring(i * 2, i * 2 + 2), 16)) return false;
        }
        return true;
    }

    private static boolean sameService(StructStat first, StructStat second) {
        return OsConstants.S_ISREG(second.st_mode) && first.st_dev == second.st_dev && first.st_ino == second.st_ino
                && first.st_size == second.st_size && first.st_mode == second.st_mode && first.st_uid == second.st_uid
                && first.st_gid == second.st_gid && first.st_mtime == second.st_mtime;
    }

    private static String property(Method get, String key) throws Exception {
        Object value = get.invoke(null, key);
        if (!(value instanceof String)) throw new IllegalStateException("property_type");
        return (String) value;
    }

    private static int transact(final IBinder binder, final byte[] payload) throws Exception {
        return bounded(new Callable<Integer>() {
            public Integer call() throws Exception { return rawTransact(binder, payload); }
        });
    }

    private static int rawTransact(IBinder binder, byte[] payload) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel strings = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            strings.unmarshall(payload, 0, payload.length);
            data.appendFrom(strings, 0, payload.length);
            if (!binder.transact(42, data, reply, 0)) throw new IllegalStateException("transaction_rejected");
            if (reply.dataSize() != 4) throw new IllegalStateException("unexpected_reply_length");
            reply.setDataPosition(0);
            return reply.readInt();
        } finally {
            reply.recycle();
            strings.recycle();
            data.recycle();
        }
    }

    private static <T> T bounded(final Callable<T> operation) throws Exception {
        return bounded(operation, CALL_TIMEOUT_MS);
    }

    private static <T> T bounded(final Callable<T> operation, long timeoutMillis) throws Exception {
        if (!BINDER_IN_FLIGHT.compareAndSet(false, true)) throw new IllegalStateException("binder_in_flight");
        FutureTask<T> task = new FutureTask<T>(new Callable<T>() {
            public T call() throws Exception {
                try {
                    return operation.call();
                } finally {
                    BINDER_IN_FLIGHT.set(false);
                }
            }
        });
        Thread thread = new Thread(task, "panel-adb-binder");
        thread.setDaemon(true);
        try {
            thread.start();
        } catch (RuntimeException error) {
            BINDER_IN_FLIGHT.set(false);
            throw error;
        }
        try {
            return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof LinkageError) throw (LinkageError) cause;
            throw new IllegalStateException("binder_worker_failed");
        }
    }

    private static boolean tcpOpen(int timeout) {
        Socket socket = new Socket();
        try {
            InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            socket.connect(new InetSocketAddress(loopback, 5555), Math.max(1, timeout));
            return true;
        } catch (Exception error) {
            return false;
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean stableDaemon(String value) {
        return value.length() == 0 || "stopped".equals(value) || "running".equals(value);
    }

    private static String portLabel(String value) {
        return AdbNetworkProtocol.allowedPort(value) ? value.length() == 0 ? "unset" : value : "other";
    }

    private static String daemonLabel(String value) {
        return value.length() == 0 ? "unset" : value.matches("running|stopped|stopping|restarting") ? value : "other";
    }

    private static String errorName(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
        String name = cause.getClass().getSimpleName();
        return name.matches("[A-Za-z][A-Za-z0-9_$]{0,63}") ? name : "unavailable";
    }

    private static void add(StringBuilder result, String line) {
        if (result.length() + line.length() + 1 <= MAX_RESULT) result.append(line).append('\n');
    }

    private static String finish(StringBuilder result, String state) {
        add(result, "result=" + state);
        return result.toString();
    }
}
