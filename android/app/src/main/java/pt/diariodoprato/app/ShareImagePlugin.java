package pt.diariodoprato.app;

import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import androidx.core.content.FileProvider;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Opens Android's share sheet (WhatsApp, Instagram, Messages…) with a picture made by the app.
 * share({ image: base64 PNG/JPEG, text, title }).
 */
@CapacitorPlugin(name = "ShareImage")
public class ShareImagePlugin extends Plugin {

    @PluginMethod
    public void share(PluginCall call) {
        String b64 = call.getString("image");
        String text = call.getString("text", "");
        String title = call.getString("title", "Partilhar");
        if (b64 == null || b64.isEmpty()) {
            call.reject("Sem imagem.");
            return;
        }
        boolean jpeg = b64.startsWith("data:image/jpeg");
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1);
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            if (bytes.length < 100 || bytes.length > 15_000_000) throw new Exception("Imagem inválida.");
            File dir = new File(getContext().getCacheDir(), "share");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("Sem espaço.");
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            File out = new File(dir, jpeg ? "NutriLente.jpg" : "NutriLente.png");
            try (FileOutputStream fo = new FileOutputStream(out)) { fo.write(bytes); }
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", out);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(jpeg ? "image/jpeg" : "image/png");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            if (text != null && !text.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, title);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().startActivity(chooser);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage() != null ? e.getMessage() : "Não foi possível partilhar.");
        }
    }
}
