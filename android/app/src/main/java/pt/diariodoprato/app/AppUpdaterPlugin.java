package pt.diariodoprato.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

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
 * Downloads a new APK from this app's GitHub releases and opens the Android installer,
 * so the user updates without leaving the app.
 */
@CapacitorPlugin(name = "AppUpdater")
public class AppUpdaterPlugin extends Plugin {

    private static final String ALLOWED_PREFIX = "https://github.com/chibangar/diario-do-prato/releases/download/";

    private volatile String state = "idle"; // idle | downloading | done | error
    private volatile int progress = 0;      // 0..100, -1 when the size is unknown
    private volatile String error = null;
    private volatile File apkFile = null;

    @PluginMethod
    public void canInstall(PluginCall call) {
        boolean allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getContext().getPackageManager().canRequestPackageInstalls();
        JSObject r = new JSObject();
        r.put("allowed", allowed);
        call.resolve(r);
    }

    @PluginMethod
    public void openInstallSettings(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        }
        call.resolve();
    }

    @PluginMethod
    public void download(PluginCall call) {
        final String url = call.getString("url");
        if (url == null || !url.startsWith(ALLOWED_PREFIX) || !url.endsWith(".apk")) {
            call.reject("Endereço de atualização inválido.");
            return;
        }
        if ("downloading".equals(state)) {
            call.resolve();
            return;
        }
        state = "downloading";
        progress = 0;
        error = null;
        apkFile = null;
        new Thread(() -> runDownload(url)).start();
        call.resolve();
    }

    private void runDownload(String url) {
        HttpURLConnection conn = null;
        try {
            File dir = new File(getContext().getCacheDir(), "updates");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("Sem espaço para a atualização.");
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            File out = new File(dir, "DiarioDoPrato-update.apk");

            // GitHub answers with redirects to its file servers: follow up to 5 of them.
            URL u = new URL(url);
            for (int hop = 0; ; hop++) {
                conn = (HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "DiarioDoPrato-app");
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
            if (total <= 0) progress = -1;
            try (InputStream in = conn.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    fo.write(buf, 0, n);
                    got += n;
                    if (total > 0) progress = (int) Math.min(99, got * 100 / total);
                }
            }
            if (out.length() < 100_000) throw new Exception("O ficheiro descarregado está incompleto.");
            apkFile = out;
            progress = 100;
            state = "done";
        } catch (Exception e) {
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

    @PluginMethod
    public void install(PluginCall call) {
        File f = apkFile;
        if (f == null || !f.exists()) {
            call.reject("A atualização ainda não foi descarregada.");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Não foi possível abrir o instalador.");
        }
    }
}
