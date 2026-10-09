package io.testhub.platform.desktop;

/** An agent for isolated desktop engines, not for the control-plane JVM. */
public final class DesktopEngineWatchdog {
    private DesktopEngineWatchdog() {}

    public static void premain(String ignored) {
        Thread watcher = new Thread(() -> {
            try { while (System.in.read() != -1) { /* The native runner owns the open pipe. */ } }
            catch (java.io.IOException closed) { /* A broken pipe also means the owner has gone. */ }
            // The runner may have been killed; do not rely on its shutdown hooks or leave load running.
            Runtime.getRuntime().halt(130);
        }, "testhub-engine-parent-watchdog");
        watcher.setDaemon(true);
        watcher.start();
    }
}
