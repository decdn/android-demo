package dev.decdn.demo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * A sponsord onramp: the gate page that sponsors a key, and the endpoint that hands out the
 * capability issued to it. The same handshake as the onramp's {@code decdn.sh} installer.
 */
final class Onramp {
    /** The deCDN sponsored onramp. */
    static final String URL_BASE = "https://up.decdn.org";

    /** A capability and the Unix second it expires at. */
    static final class Capability {
        final String token;
        final long expiry;

        Capability(String token, long expiry) {
            this.token = token;
            this.expiry = expiry;
        }
    }

    private Onramp() {}

    /** The page where a person passes the gate for {@code address}. */
    static String fundUrl(String address) {
        return URL_BASE + "/fund?client=" + address;
    }

    /**
     * The capability issued to {@code address}, or {@code null} while none is (the person has not
     * passed the gate yet, or the onramp rate-limits this poll).
     */
    static Capability capability(String address) throws IOException {
        HttpURLConnection conn =
                (HttpURLConnection) new URL(URL_BASE + "/v1/capability?client=" + address)
                        .openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        try {
            int code = conn.getResponseCode();
            if (code == 204 || code == 429) {
                return null;
            }
            if (code != 200) {
                throw new IOException("onramp answered " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    body.write(buf, 0, n);
                }
                JSONObject json = new JSONObject(body.toString(StandardCharsets.UTF_8.name()));
                return new Capability(json.getString("token"), json.getLong("expiry"));
            } catch (org.json.JSONException e) {
                throw new IOException("unexpected onramp answer: " + e.getMessage());
            }
        } finally {
            conn.disconnect();
        }
    }
}
