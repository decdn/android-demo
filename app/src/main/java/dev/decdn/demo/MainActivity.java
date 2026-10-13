package dev.decdn.demo;

import android.app.Activity;
import android.content.ContentValues;
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
 * One screen: a content hash, a namespace and a capability in; verified files in Downloads/decdn
 * out. A pull fetches one blob, or with "Whole bundle" every file a bundle manifest lists. The
 * throwaway key is loaded into files/key/ with {@code ./build.sh key DIR}.
 */
public class MainActivity extends Activity {
    private static final String DEFAULT_HASH = "";
    private static final String[] PHASES = {"Starting", "Finding nodes", "Downloading", "Verifying"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private File home;
    private File keyDir;
    private EditText hash;
    private EditText namespace;
    private EditText fileName;
    private CheckBox wholeBundle;
    private EditText capability;
    private Button download;
    private TextView status;
    private TextView details;
    private volatile boolean running;
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
        File cap = new File(keyDir, "capability");
        if (cap.exists()) {
            capability.setText(read(cap).trim());
        }
        status.setText(cap.exists() ? "Ready." : "No key yet: run ./build.sh key DIR");
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
        capability = field(col, "Capability (dcap1:…)", "");
        capability.setMaxLines(3);

        download = new Button(this);
        download.setText("Download");
        download.setOnClickListener(v -> {
            if (running) {
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
        String cap = capability.getText().toString().trim();
        boolean bundle = wholeBundle.isChecked();
        if (h.isEmpty() || cap.isEmpty() || name.isEmpty()) {
            status.setText("Hash, name and capability are required.");
            return;
        }
        running = true;
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
                result = pull(h, ns, name, cap, bundle);
            } catch (Exception e) {
                result = "Failed: " + e;
            }
            String done = result;
            ui.post(() -> {
                running = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                status.setText(done);
                download.setText("Download");
            });
        }, "decdn-pull").start();
    }

    /** Runs on the worker thread. Returns the status line to show. */
    private String pull(String h, String ns, String name, String cap, boolean bundle)
            throws Exception {
        File keystore = new File(keyDir, "keystore.json");
        File password = new File(keyDir, "password");
        if (!keystore.exists() || !password.exists()) {
            return "No key: run ./build.sh key DIR";
        }
        File capFile = new File(keyDir, "capability");
        Files.write(capFile.toPath(), cap.getBytes(StandardCharsets.UTF_8));
        Os.chmod(capFile.getAbsolutePath(), 0600);

        File data = new File(home, "data");
        data.mkdirs();
        Os.chmod(data.getAbsolutePath(), 0700);
        File downloads = new File(home, "downloads");
        File outDir = bundle ? new File(downloads, name) : downloads;
        outDir.mkdirs();

        long nsValue = ns.isEmpty() ? -1 : Long.parseLong(ns);
        String reply = Native.pull(h, nsValue, outDir.getAbsolutePath(), name,
                keyDir.getAbsolutePath(), data.getAbsolutePath(), bundle ? 1 : 0);
        String summary = reply.length() > 3 ? reply.substring(reply.indexOf(' ') + 1) : reply;
        ui.post(() -> details.setText(summary));
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
