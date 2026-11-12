package pt.diariodoprato.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * Reads the printed text in a photo (product names and brands on packaging) with Google ML Kit,
 * on the phone and without internet. read({ image: base64 JPEG }) resolves { text, lines: [{ text, h }] }.
 */
@CapacitorPlugin(name = "TextReader")
public class TextReaderPlugin extends Plugin {

    @PluginMethod
    public void read(PluginCall call) {
        String b64 = call.getString("image");
        if (b64 == null || b64.isEmpty()) {
            call.reject("Sem imagem.");
            return;
        }
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1);
        Bitmap bmp;
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            call.reject("Imagem inválida.");
            return;
        }
        if (bmp == null) {
            call.reject("Imagem inválida.");
            return;
        }
        final TextRecognizer reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        reader.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener(result -> {
                JSArray lines = new JSArray();
                for (Text.TextBlock block : result.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        JSObject o = new JSObject();
                        o.put("text", line.getText());
                        Rect box = line.getBoundingBox();
                        o.put("h", box != null ? box.height() : 0);
                        lines.put(o);
                    }
                }
                JSObject res = new JSObject();
                res.put("text", result.getText());
                res.put("lines", lines);
                reader.close();
                call.resolve(res);
            })
            .addOnFailureListener(e -> {
                reader.close();
                call.reject("Não consegui ler o texto: " + e.getMessage());
            });
    }
}
