package pt.diariodoprato.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Keeps the user's Gemini key in the app's private Android storage as well as in the web page's storage,
 * so it is still there after the app is closed, updated or the page's storage is cleared.
 * Only the names listed in ALLOWED can be stored.
 */
@CapacitorPlugin(name = "KeyStore")
public class KeyStorePlugin extends Plugin {

    private static final String FILE = "nutrilente_keys";
    private static final String[] ALLOWED = { "aiKey" };

    private static boolean allowed(String name) {
        if (name == null) return false;
        for (String a : ALLOWED) if (a.equals(name)) return true;
        return false;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @PluginMethod
    public void get(PluginCall call) {
        String name = call.getString("name");
        if (!allowed(name)) {
            call.reject("Nome inválido.");
            return;
        }
        JSObject r = new JSObject();
        r.put("value", prefs().getString(name, ""));
        call.resolve(r);
    }

    @PluginMethod
    public void set(PluginCall call) {
        String name = call.getString("name");
        String value = call.getString("value", "");
        if (!allowed(name) || value == null || value.length() > 300) {
            call.reject("Valor inválido.");
            return;
        }
        boolean ok = value.isEmpty() ? prefs().edit().remove(name).commit() : prefs().edit().putString(name, value).commit();
        if (ok) call.resolve();
        else call.reject("Não consegui guardar.");
    }
}
