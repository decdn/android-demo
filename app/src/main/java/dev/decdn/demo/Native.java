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
     * The address of the throwaway key in {@code keyFile}, generating a random key there first
     * when the file is absent. Returns {@code "0x…"} or {@code "ERR <message>"}.
     */
    static native String address(String keyFile);

    /**
     * Pulls {@code hash} into {@code outDir}, blocking until it ends. With {@code bundle} nonzero
     * the hash names a bundle manifest and every entry lands under {@code outDir}; otherwise the
     * blob lands at {@code outDir/fileName}. A negative {@code namespace} sends none. The key in
     * {@code keyFile} pays under {@code capability}. Returns {@code "OK <summary>"},
     * {@code "FUNDING <message>"} when the capability cannot pay for the rest, or
     * {@code "ERR <message>"}.
     */
    static native String pull(String hash, long namespace, String outDir, String fileName,
            String keyFile, String capability, String dataDir, int bundle);

    /** The running pull: {@code "phase,received,expected,filesDone,filesTotal"}. */
    static native String progress();

    /** Stops the running pull; a rerun resumes it. */
    static native void cancel();
}
