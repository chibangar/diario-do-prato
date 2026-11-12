package pt.diariodoprato.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Keeps the food-recognition model on the phone. The model is a file in this app's GitHub releases;
 * GitHub's file servers do not allow a web page to read them, so the download happens here.
 * download({ url, name, size }) starts it, status() reports progress, info({ name }) says where the file is.
 */
@CapacitorPlugin(name = "ModelStore")
public class ModelStorePlugin extends Plugin {

    private static final String ALLOWED_PREFIX = "https://github.com/chibangar/diario-do-prato/releases/download/";

    private volatile String state = "idle"; // idle | downloading | done | error
    private volatile int progress = 0;      // 0..100
    private volatile String error = null;

    private File dir() {
        return new File(getContext().getFilesDir(), "models");
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9._-]{1,80}") && name.endsWith(".onnx");
    }

    @PluginMethod
    public void info(PluginCall call) {
        String name = call.getString("name");
        if (!validName(name)) {
            call.reject("Nome inválido.");
            return;
        }
        File f = new File(dir(), name);
        JSObject r = new JSObject();
        r.put("exists", f.isFile());
        r.put("size", f.isFile() ? f.length() : 0);
        r.put("path", f.getAbsolutePath());
        call.resolve(r);
    }

    @PluginMethod
    public void download(PluginCall call) {
        final String url = call.getString("url");
        final String name = call.getString("name");
        final long size = call.getLong("size", 0L);
        if (url == null || !url.startsWith(ALLOWED_PREFIX) || !validName(name)) {
            call.reject("Endereço inválido.");
            return;
        }
        if ("downloading".equals(state)) {
            call.resolve();
            return;
        }
        state = "downloading";
        progress = 0;
        error = null;
        new Thread(() -> runDownload(url, name, size)).start();
        call.resolve();
    }

    private void runDownload(String url, String name, long expected) {
        HttpURLConnection conn = null;
        File part = null;
        try {
            File d = dir();
            if (!d.exists() && !d.mkdirs()) throw new Exception("Sem espaço no telemóvel.");
            part = new File(d, name + ".part");
            File out = new File(d, name);

            // GitHub answers with redirects to its file servers: follow up to 5 of them.
            URL u = new URL(url);
            for (int hop = 0; ; hop++) {
                conn = (HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "NutriLente-app");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400 && hop < 5) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null) throw new Exception("Resposta inesperada do servidor.");
                    u = new URL(u, location);
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) throw new Exception("O servidor respondeu " + code + ".");
                break;
            }

            long total = conn.getContentLengthLong();
            if (total <= 0) total = expected;
            try (InputStream in = conn.getInputStream(); FileOutputStream fo = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    fo.write(buf, 0, n);
                    got += n;
                    if (total > 0) progress = (int) Math.min(99, got * 100 / total);
                }
            }
            if (expected > 0 && part.length() != expected) throw new Exception("O ficheiro descarregado está incompleto.");
            // only one model is kept: older ones are removed
            File[] old = d.listFiles();
            if (old != null) for (File f : old) if (!f.equals(part)) //noinspection ResultOfMethodCallIgnored
                f.delete();
            if (!part.renameTo(out)) throw new Exception("Não consegui guardar o ficheiro.");
            progress = 100;
            state = "done";
        } catch (Exception e) {
            if (part != null) //noinspection ResultOfMethodCallIgnored
                part.delete();
            error = e.getMessage() != null ? e.getMessage() : "Falha na ligação.";
            state = "error";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("state", state);
        r.put("progress", progress);
        r.put("error", error);
        call.resolve(r);
    }
}
