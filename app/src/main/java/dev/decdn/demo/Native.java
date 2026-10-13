package dev.decdn.demo;

import android.content.Context;

/** The deCDN client SDK, loaded as a shared library. */
final class Native {
    static {
        System.loadLibrary("decdn_android");
    }

    private Native() {}

    /** Hands the app context to the Rust side. Returns 0 on success. */
    static native int init(Context context);

    /**
     * Pulls {@code hash} into {@code outDir}, blocking until it ends. With {@code bundle} nonzero
     * the hash names a bundle manifest and every entry lands under {@code outDir}; otherwise the
     * blob lands at {@code outDir/fileName}. A negative {@code namespace} sends none. Returns
     * {@code "OK <summary>"} or {@code "ERR <message>"}.
     */
    static native String pull(String hash, long namespace, String outDir, String fileName,
            String keyDir, String dataDir, int bundle);

    /** The running pull: {@code "phase,received,expected,filesDone,filesTotal"}. */
    static native String progress();

    /** Stops the running pull; a rerun resumes it. */
    static native void cancel();
}
