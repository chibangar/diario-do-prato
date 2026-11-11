package pt.diariodoprato.app;

import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Plays the welcome voice (www/welcome.mp3, packaged as assets/public/welcome.mp3)
 * with Android's own MediaPlayer, which is more reliable than the WebView audio element.
 * play() resolves when the voice finishes or is stopped.
 */
@CapacitorPlugin(name = "WelcomeVoice")
public class WelcomeVoicePlugin extends Plugin {

    private MediaPlayer player;
    private PluginCall pending;

    @PluginMethod
    public void play(PluginCall call) {
        stopInternal(true);
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            try (AssetFileDescriptor afd = getContext().getAssets().openFd("public/welcome.mp3")) {
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            }
            mp.setOnCompletionListener(m -> finish(false));
            mp.setOnErrorListener((m, what, extra) -> {
                PluginCall c = pending;
                pending = null;
                release();
                if (c != null) c.reject("Erro ao tocar o som (" + what + ").");
                return true;
            });
            mp.prepare();
            player = mp;
            pending = call;
            mp.start();
        } catch (Exception e) {
            release();
            call.reject("Não foi possível tocar a voz: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopInternal(true);
        call.resolve();
    }

    /** How loud the phone's media volume is (0 to 1), so the page can warn when it is muted. */
    @PluginMethod
    public void volume(PluginCall call) {
        AudioManager am = (AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
        JSObject r = new JSObject();
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            r.put("level", max > 0 ? (double) cur / max : 1.0);
        } else {
            r.put("level", 1.0);
        }
        call.resolve(r);
    }

    private void stopInternal(boolean stopped) {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
        }
        finish(stopped);
    }

    private void finish(boolean stopped) {
        PluginCall c = pending;
        pending = null;
        release();
        if (c != null) {
            JSObject r = new JSObject();
            r.put("stopped", stopped);
            c.resolve(r);
        }
    }

    private void release() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    @Override
    protected void handleOnPause() {
        stopInternal(true);
    }

    @Override
    protected void handleOnDestroy() {
        stopInternal(true);
    }
}
