package dev.decdn.demo;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.system.Os;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One screen: a content hash and a namespace in; verified files in Downloads/decdn out. A pull
 * fetches one blob, or with "Whole bundle" every file a bundle manifest lists.
 *
 * <p>The app pays with a random throwaway key it keeps in its private storage. Before the first
 * pull, and whenever the allowance is spent or near expiry, it sends the person to the sponsored
 * onramp's gate page in the browser and picks up the capability issued to its key.
 */
public class MainActivity extends Activity {
    private static final String DEFAULT_HASH = "";
    private static final String[] PHASES = {"Starting", "Finding nodes", "Downloading", "Verifying"};
    /** A saved capability with less life than this is replaced before a pull, as decdn.sh does. */
    private static final long EXPIRY_MARGIN_S = 4020;
    private static final long GATE_TIMEOUT_MS = 600_000;
    private static final long GATE_POLL_MS = 2_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private File home;
    private File keyDir;
    private EditText hash;
    private EditText namespace;
    private EditText fileName;
    private CheckBox wholeBundle;
    private TextView allowance;
    private Button download;
    private TextView status;
    private TextView details;
    private volatile boolean running;
    private volatile boolean cancelled;
    /** A status line that replaces the progress readout while set, such as the gate wait. */
    private volatile String stage;
    private long startedAt;
    private long lastBytes;
    private long lastPollAt;
    private double rate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        home = getFilesDir();
        keyDir = new File(home, "key");
        buildUi();
        try {
            Os.setenv("HOME", home.getAbsolutePath(), true);
        } catch (Exception e) {
            status.setText("setenv HOME failed: " + e);
            return;
        }
        if (Native.init(getApplicationContext()) != 0) {
            status.setText("native init failed");
            download.setEnabled(false);
            return;
        }
        refreshAllowance();
        status.setText("Ready.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (allowance != null && download.isEnabled()) {
            refreshAllowance();
        }
    }

    /** Shows the saved allowance and the key it belongs to. */
    private void refreshAllowance() {
        keyDir.mkdirs();
        String address = Native.address(keyFile().getAbsolutePath());
        String key = address.startsWith("0x") && address.length() > 12
                ? address.substring(0, 6) + "…" + address.substring(address.length() - 4)
                : address;
        long expiry = parseLong(read(new File(keyDir, "expiry")).trim());
        long now = System.currentTimeMillis() / 1000;
        if (new File(keyDir, "capability").exists() && expiry > now + EXPIRY_MARGIN_S) {
            String until = new java.text.SimpleDateFormat("MMM d, HH:mm", Locale.US)
                    .format(new java.util.Date(expiry * 1000));
            allowance.setText("Sponsored allowance for key " + key + ", valid until " + until);
        } else {
            allowance.setText("Key " + key + " has no allowance yet. Download opens a quick "
                    + "check in your browser first.");
        }
    }

    private void buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("deCDN download");
        title.setTextSize(24);
        col.addView(title);

        hash = field(col, "Content hash (BLAKE3 hex)", DEFAULT_HASH);
        namespace = field(col, "Namespace", "1");
        namespace.setInputType(InputType.TYPE_CLASS_NUMBER);
        fileName = field(col, "Save as (file, or folder for a bundle)", "decdn-demo.bin");
        wholeBundle = new CheckBox(this);
        wholeBundle.setText("Whole bundle (the hash is a bundle manifest)");
        col.addView(wholeBundle);
        allowance = new TextView(this);
        allowance.setPadding(0, dp(12), 0, dp(8));
        col.addView(allowance);

        download = new Button(this);
        download.setText("Download");
        download.setOnClickListener(v -> {
            if (running) {
                cancelled = true;
                Native.cancel();
                status.setText("Stopping…");
            } else {
                start();
            }
        });
        col.addView(download);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(12), 0, dp(12));
        col.addView(status);

        details = new TextView(this);
        details.setTypeface(Typeface.MONOSPACE);
        details.setTextSize(11);
        details.setTextIsSelectable(true);
        col.addView(details);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        // Edge-to-edge: keep content clear of the status bar, nav bar and keyboard.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.ime()
                            | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);
    }

    private EditText field(LinearLayout parent, String hint, String value) {
        TextView label = new TextView(this);
        label.setText(hint);
        label.setPadding(0, dp(12), 0, 0);
        parent.addView(label);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(false);
        e.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(e);
        return e;
    }

    private void start() {
        String h = hash.getText().toString().trim();
        String ns = namespace.getText().toString().trim();
        String name = fileName.getText().toString().trim();
        boolean bundle = wholeBundle.isChecked();
        if (h.isEmpty() || name.isEmpty()) {
            status.setText("Hash and name are required.");
            return;
        }
        running = true;
        cancelled = false;
        stage = null;
        // Android cuts a sleeping app's network, so the screen stays on while a pull runs.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        download.setText("Cancel");
        details.setText("");
        startedAt = System.currentTimeMillis();
        lastBytes = 0;
        lastPollAt = 0;
        rate = 0;
        ui.post(this::poll);
        new Thread(() -> {
            String result;
            try {
                result = pull(h, ns, name, bundle);
            } catch (Exception e) {
                result = "Failed: " + e;
            }
            String done = result;
            ui.post(() -> {
                running = false;
                stage = null;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                status.setText(done);
                download.setText("Download");
                refreshAllowance();
            });
        }, "decdn-pull").start();
    }

    /** Runs on the worker thread. Returns the status line to show. */
    private String pull(String h, String ns, String name, boolean bundle) throws Exception {
        String cap = ensureCapability();
        if (cap == null) {
            return cancelled ? "Cancelled." : "No allowance yet: the browser check timed out.";
        }
        stage = null;

        File data = new File(home, "data");
        data.mkdirs();
        Os.chmod(data.getAbsolutePath(), 0700);
        File downloads = new File(home, "downloads");
        File outDir = bundle ? new File(downloads, name) : downloads;
        outDir.mkdirs();

        long nsValue = ns.isEmpty() ? -1 : Long.parseLong(ns);
        String reply = Native.pull(h, nsValue, outDir.getAbsolutePath(), name,
                keyFile().getAbsolutePath(), cap, data.getAbsolutePath(), bundle ? 1 : 0);
        String summary = reply.length() > 3 ? reply.substring(reply.indexOf(' ') + 1) : reply;
        ui.post(() -> details.setText(summary));
        if (reply.startsWith("FUNDING")) {
            // A key's allowance is fixed once issued: the next one goes to a fresh key.
            rotateKey();
            return "The allowance ran out. Tap Download for a new one; the download resumes.";
        }
        if (!reply.startsWith("OK")) {
            return "Download failed. See below.";
        }

        List<File> files = new ArrayList<>();
        if (bundle) {
            collect(outDir, files);
        } else {
            files.add(new File(outDir, name));
        }
        long total = 0;
        int copied = 0;
        String base = Environment.DIRECTORY_DOWNLOADS + "/decdn"
                + (bundle ? "/" + name : "");
        for (File f : files) {
            String rel = outDir.toPath().relativize(f.getParentFile().toPath()).toString();
            if (saveToDownloads(f, rel.isEmpty() || !bundle ? base : base + "/" + rel)) {
                copied++;
            }
            total += f.length();
        }
        double secs = (System.currentTimeMillis() - startedAt) / 1000.0;
        return String.format(Locale.US, "Done: %d file%s, %s verified in %.1f s. Saved %d to %s",
                files.size(), files.size() == 1 ? "" : "s", size(total), secs, copied, base);
    }

    /**
     * The capability for this app's key: the saved one while it has more than {@link
     * #EXPIRY_MARGIN_S} of life, else the one the onramp issues after the person passes the gate
     * in the browser. Runs on the worker thread. Returns {@code null} on cancel or timeout.
     */
    private String ensureCapability() throws Exception {
        keyDir.mkdirs();
        File capFile = new File(keyDir, "capability");
        File expFile = new File(keyDir, "expiry");
        long now = System.currentTimeMillis() / 1000;
        if (capFile.exists()) {
            if (parseLong(read(expFile).trim()) > now + EXPIRY_MARGIN_S) {
                return read(capFile).trim();
            }
            // Too close to expiry to start on, and a key's allowance cannot be renewed.
            rotateKey();
        }
        String address = Native.address(keyFile().getAbsolutePath());
        if (!address.startsWith("0x")) {
            throw new IOException(address);
        }
        stage = "Checking for an allowance…";
        Onramp.Capability cap = Onramp.capability(address);
        if (cap == null) {
            stage = "Pass the check in your browser, then come back here.";
            ui.post(() -> startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(Onramp.fundUrl(address)))));
            long deadline = System.currentTimeMillis() + GATE_TIMEOUT_MS;
            while (cap == null) {
                if (cancelled || System.currentTimeMillis() > deadline) {
                    return null;
                }
                Thread.sleep(GATE_POLL_MS);
                try {
                    cap = Onramp.capability(address);
                } catch (IOException e) {
                    // Android cuts a background app's network while the browser is in front,
                    // so a poll fails until the person comes back; keep polling.
                }
            }
        }
        writePrivate(capFile, cap.token);
        writePrivate(expFile, Long.toString(cap.expiry));
        ui.post(this::refreshAllowance);
        return cap.token;
    }

    /** Drops the key and its allowance; the next pull makes a fresh key. */
    private void rotateKey() {
        keyFile().delete();
        new File(keyDir, "capability").delete();
        new File(keyDir, "expiry").delete();
    }

    private File keyFile() {
        return new File(keyDir, "key.hex");
    }

    private static void writePrivate(File f, String s) throws Exception {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
        Os.chmod(f.getAbsolutePath(), 0600);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Every finished file under {@code dir}, skipping the pull's dot-files and partials. */
    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            String n = f.getName();
            if (n.startsWith(".") || n.endsWith(".partial") || n.endsWith(".partial.ranges")) {
                continue;
            }
            if (f.isDirectory()) {
                collect(f, out);
            } else {
                out.add(f);
            }
        }
    }

    private void poll() {
        if (!running) {
            return;
        }
        String s0 = stage;
        if (s0 != null) {
            status.setText(s0);
            ui.postDelayed(this::poll, 500);
            return;
        }
        String[] p = Native.progress().split(",");
        int phase = Integer.parseInt(p[0]);
        long received = Long.parseLong(p[1]);
        long expected = Long.parseLong(p[2]);
        long filesDone = Long.parseLong(p[3]);
        long filesTotal = Long.parseLong(p[4]);
        long now = System.currentTimeMillis();
        double secs = (now - startedAt) / 1000.0;

        if (lastPollAt > 0 && now > lastPollAt && received >= lastBytes) {
            double instant = (received - lastBytes) * 1000.0 / (now - lastPollAt);
            rate = rate == 0 ? instant : 0.7 * rate + 0.3 * instant;
        }
        lastBytes = received;
        lastPollAt = now;

        // Phase 0 after bytes landed: the pull is over and the copy to Downloads runs.
        if (phase == 0 && received > 0) {
            status.setText(String.format(Locale.US, "Saving to Downloads… %.0f s", secs));
            ui.postDelayed(this::poll, 500);
            return;
        }
        StringBuilder s = new StringBuilder(PHASES[Math.min(phase, PHASES.length - 1)]);
        s.append("… ");
        if (received > 0 || expected > 0) {
            s.append(size(received));
            if (expected > 0) {
                s.append(" of ").append(size(expected)).append(String.format(Locale.US,
                        " (%.0f%%)", 100.0 * received / expected));
            }
            s.append(", ").append(size((long) rate)).append("/s");
        }
        if (filesTotal > 1) {
            s.append(String.format(Locale.US, ", %d/%d files", filesDone, filesTotal));
        }
        s.append(String.format(Locale.US, ", %.0f s", secs));
        status.setText(s.toString());
        ui.postDelayed(this::poll, 500);
    }

    private boolean saveToDownloads(File src, String relativePath) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, src.getName());
        v.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            return false;
        }
        try (InputStream in = new FileInputStream(src);
             OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) {
                return false;
            }
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1 << 20) return String.format(Locale.US, "%.1f KiB", b / 1024.0);
        if (b < 1 << 30) return String.format(Locale.US, "%.1f MiB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GiB", b / 1073741824.0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
