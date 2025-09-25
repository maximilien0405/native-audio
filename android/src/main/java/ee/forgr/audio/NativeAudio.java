package ee.forgr.audio;

import static ee.forgr.audio.Constant.ASSET_ID;
import static ee.forgr.audio.Constant.ASSET_PATH;
import static ee.forgr.audio.Constant.AUDIO_CHANNEL_NUM;
import static ee.forgr.audio.Constant.ERROR_ASSET_NOT_LOADED;
import static ee.forgr.audio.Constant.ERROR_ASSET_PATH_MISSING;
import static ee.forgr.audio.Constant.ERROR_AUDIO_ASSET_MISSING;
import static ee.forgr.audio.Constant.ERROR_AUDIO_EXISTS;
import static ee.forgr.audio.Constant.ERROR_AUDIO_ID_MISSING;
import static ee.forgr.audio.Constant.LOOP;
import static ee.forgr.audio.Constant.OPT_FOCUS_AUDIO;
import static ee.forgr.audio.Constant.RATE;
import static ee.forgr.audio.Constant.VOLUME;

import android.Manifest;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.media3.common.util.UnstableApi;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@UnstableApi
@CapacitorPlugin(
    permissions = {
        @Permission(strings = { Manifest.permission.MODIFY_AUDIO_SETTINGS }),
        @Permission(strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE }),
        @Permission(strings = { Manifest.permission.READ_PHONE_STATE })
    }
)
public class NativeAudio extends Plugin implements AudioManager.OnAudioFocusChangeListener {

    public static final String TAG = "NativeAudio";

    private static HashMap<String, AudioAsset> audioAssetList = new HashMap<>();
    private static ArrayList<AudioAsset> resumeList;
    private AudioManager audioManager;
    private boolean fadeMusic = false;

    private final Map<String, PluginCall> pendingDurationCalls = new HashMap<>();

    @Override
    public void load() {
        super.load();

        this.audioManager = (AudioManager) this.getActivity().getSystemService(Context.AUDIO_SERVICE);

        audioAssetList = new HashMap<>();
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        try {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                // Pause playback - temporary loss
                for (AudioAsset audio : audioAssetList.values()) {
                    if (audio.isPlaying()) {
                        audio.pause();
                        resumeList.add(audio);
                    }
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                // Resume playback
                if (resumeList != null) {
                    while (!resumeList.isEmpty()) {
                        AudioAsset audio = resumeList.remove(0);
                        audio.resume();
                    }
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                // Stop playback - permanent loss
                for (AudioAsset audio : audioAssetList.values()) {
                    audio.stop();
                }
                audioManager.abandonAudioFocus(this);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error handling audio focus change", ex);
        }
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();

        try {
            if (audioAssetList != null) {
                for (HashMap.Entry<String, AudioAsset> entry : audioAssetList.entrySet()) {
                    AudioAsset audio = entry.getValue();

                    if (audio != null) {
                        boolean wasPlaying = audio.pause();

                        if (wasPlaying) {
                            resumeList.add(audio);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Log.d(TAG, "Exception caught while listening for handleOnPause: " + ex.getLocalizedMessage());
        }
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();

        try {
            if (resumeList != null) {
                while (!resumeList.isEmpty()) {
                    AudioAsset audio = resumeList.remove(0);

                    if (audio != null) {
                        audio.resume();
                    }
                }
            }
        } catch (Exception ex) {
            Log.d(TAG, "Exception caught while listening for handleOnResume: " + ex.getLocalizedMessage());
        }
    }

    @PluginMethod
    public void configure(PluginCall call) {
        initSoundPool();

        if (this.audioManager == null) {
            call.resolve();
            return;
        }

        boolean focus = call.getBoolean(OPT_FOCUS_AUDIO, false);
        boolean background = call.getBoolean("background", false);
        this.fadeMusic = call.getBoolean("fade", false);

        try {
            if (focus) {
                // Request audio focus for playback with ducking
                int result =
                    this.audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK); // Allow other audio to play quietly
            } else {
                this.audioManager.abandonAudioFocus(this);
            }

            if (background) {
                // Set playback to continue in background
                this.audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            } else {
                this.audioManager.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error configuring audio", ex);
        }

        call.resolve();
    }

    @PluginMethod
    public void isPreloaded(final PluginCall call) {
        new Thread(
            new Runnable() {
                @Override
                public void run() {
                    initSoundPool();

                    String audioId = call.getString(ASSET_ID);

                    if (!isStringValid(audioId)) {
                        call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                        return;
                    }
                    call.resolve(new JSObject().put("found", audioAssetList.containsKey(audioId)));
                }
            }
        ).start();
    }

    @PluginMethod
    public void preload(final PluginCall call) {
        this.getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        preloadAsset(call);
                    }
                }
            );
    }

    @PluginMethod
    public void play(final PluginCall call) {
        this.getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        playOrLoop("play", call);
                    }
                }
            );
    }

    @PluginMethod
    public void getCurrentTime(final PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);

            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    call.resolve(new JSObject().put("currentTime", asset.getCurrentPosition()));
                }
            } else {
                call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void getDuration(PluginCall call) {
        try {
            String audioId = call.getString(ASSET_ID);
            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    double duration = asset.getDuration();
                    if (duration > 0) {
                        JSObject ret = new JSObject();
                        ret.put("duration", duration);
                        call.resolve(ret);
                    } else {
                        // Save the call to resolve it later when duration is available
                        saveDurationCall(audioId, call);
                    }
                } else {
                    call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
                }
            } else {
                call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void loop(final PluginCall call) {
        this.getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        playOrLoop("loop", call);
                    }
                }
            );
    }

    @PluginMethod
    public void pause(PluginCall call) {
        try {
            initSoundPool();
            String audioId = call.getString(ASSET_ID);

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    boolean wasPlaying = asset.pause();

                    if (wasPlaying) {
                        resumeList.add(asset);
                    }
                    call.resolve();
                } else {
                    call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
                }
            } else {
                call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void resume(PluginCall call) {
        try {
            initSoundPool();
            String audioId = call.getString(ASSET_ID);

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    asset.resume();
                    resumeList.add(asset);
                    call.resolve();
                } else {
                    call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
                }
            } else {
                call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void stop(final PluginCall call) {
        this.getActivity()
            .runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String audioId = call.getString(ASSET_ID);
                            if (!isStringValid(audioId)) {
                                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                                return;
                            }
                            stopAudio(audioId);
                            call.resolve();
                        } catch (Exception ex) {
                            call.reject(ex.getMessage());
                        }
                    }
                }
            );
    }

    @PluginMethod
    public void unload(PluginCall call) {
        try {
            initSoundPool();
            new JSObject();
            JSObject status;

            if (isStringValid(call.getString(ASSET_ID))) {
                String audioId = call.getString(ASSET_ID);

                if (audioAssetList.containsKey(audioId)) {
                    AudioAsset asset = audioAssetList.get(audioId);
                    if (asset != null) {
                        asset.unload();
                        audioAssetList.remove(audioId);
                        call.resolve();
                    } else {
                        call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
                    }
                } else {
                    call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
                }
            } else {
                call.reject(ERROR_AUDIO_ID_MISSING);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);
            float volume = call.getFloat(VOLUME, 1F);

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    asset.setVolume(volume);
                    call.resolve();
                } else {
                    call.reject(ERROR_AUDIO_ASSET_MISSING);
                }
            } else {
                call.reject(ERROR_AUDIO_ASSET_MISSING);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void setRate(PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);
            float rate = call.getFloat(RATE, 1F);

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    asset.setRate(rate);
                }
                call.resolve();
            } else {
                call.reject(ERROR_AUDIO_ASSET_MISSING);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void isPlaying(final PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);

            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    call.resolve(new JSObject().put("isPlaying", asset.isPlaying()));
                } else {
                    call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
                }
            } else {
                call.reject(ERROR_AUDIO_ASSET_MISSING + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    @PluginMethod
    public void clearCache(PluginCall call) {
        RemoteAudioAsset.clearCache(getContext());
        call.resolve();
    }

    @PluginMethod
    public void setCurrentTime(final PluginCall call) {
        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);
            Double time = call.getDouble("time", 0.0);

            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                if (asset != null) {
                    this.getActivity()
                        .runOnUiThread(
                            new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        asset.setCurrentTime(time);
                                        call.resolve();
                                    } catch (Exception e) {
                                        call.reject("Error setting current time: " + e.getMessage());
                                    }
                                }
                            }
                        );
                } else {
                    call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
                }
            } else {
                call.reject(ERROR_ASSET_NOT_LOADED + " - " + audioId);
            }
        } catch (Exception ex) {
            call.reject(ex.getMessage());
        }
    }

    public void dispatchComplete(String assetId) {
        JSObject ret = new JSObject();
        ret.put("assetId", assetId);
        notifyListeners("complete", ret);
    }

    public void notifyCurrentTime(String assetId, double currentTime) {
        // Round to nearest 100ms
        double roundedTime = Math.round(currentTime * 10.0) / 10.0;
        JSObject ret = new JSObject();
        ret.put("currentTime", roundedTime);
        ret.put("assetId", assetId);
        notifyListeners("currentTime", ret);
    }

    private void preloadAsset(PluginCall call) {
        float volume = 1F;
        int audioChannelNum = 1;
        JSObject status = new JSObject();
        status.put("STATUS", "OK");

        try {
            initSoundPool();

            String audioId = call.getString(ASSET_ID);
            if (!isStringValid(audioId)) {
                call.reject(ERROR_AUDIO_ID_MISSING + " - " + audioId);
                return;
            }

            String assetPath = call.getString(ASSET_PATH);
            if (!isStringValid(assetPath)) {
                call.reject(ERROR_ASSET_PATH_MISSING + " - " + audioId + " - " + assetPath);
                return;
            }

            boolean isLocalUrl = call.getBoolean("isUrl", false);
            boolean isComplex = call.getBoolean("isComplex", false);

            Log.d("AudioPlugin", "Debug: audioId = " + audioId + ", assetPath = " + assetPath + ", isLocalUrl = " + isLocalUrl);

            if (audioAssetList.containsKey(audioId)) {
                call.reject(ERROR_AUDIO_EXISTS + " - " + audioId);
                return;
            }

            if (isComplex) {
                volume = call.getFloat(VOLUME, 1F);
                audioChannelNum = call.getInt(AUDIO_CHANNEL_NUM, 1);
            }

            if (isLocalUrl) {
                try {
                    Uri uri = Uri.parse(assetPath);
                    if (uri.getScheme() != null && (uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                        // Remote URL
                        Log.d("AudioPlugin", "Debug: Remote URL detected: " + uri.toString());
                        if (assetPath.endsWith(".m3u8")) {
                            // HLS Stream - resolve immediately since it's a stream
                            StreamAudioAsset streamAudioAsset = new StreamAudioAsset(this, audioId, uri, volume);
                            audioAssetList.put(audioId, streamAudioAsset);
                            call.resolve(status);
                        } else {
                            // Regular remote audio
                            RemoteAudioAsset remoteAudioAsset = new RemoteAudioAsset(this, audioId, uri, audioChannelNum, volume);
                            remoteAudioAsset.setCompletionListener(this::dispatchComplete);
                            audioAssetList.put(audioId, remoteAudioAsset);
                            call.resolve(status);
                        }
                    } else if (uri.getScheme() != null && uri.getScheme().equals("file")) {
                        // Local file URL
                        Log.d("AudioPlugin", "Debug: Local file URL detected");
                        File file = new File(uri.getPath());
                        if (!file.exists()) {
                            Log.e("AudioPlugin", "Error: File does not exist - " + file.getAbsolutePath());
                            call.reject(ERROR_ASSET_PATH_MISSING + " - " + assetPath);
                            return;
                        }
                        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                        AssetFileDescriptor afd = new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
                        AudioAsset asset = new AudioAsset(this, audioId, afd, audioChannelNum, volume);
                        asset.setCompletionListener(this::dispatchComplete);
                        audioAssetList.put(audioId, asset);
                        call.resolve(status);
                    } else {
                        throw new IllegalArgumentException("Invalid URL scheme: " + uri.getScheme());
                    }
                } catch (Exception e) {
                    Log.e("AudioPlugin", "Error handling URL", e);
                    call.reject("Error handling URL: " + e.getMessage());
                }
            } else {
                // Handle asset in public folder
                Log.d("AudioPlugin", "Debug: Handling asset in public folder");
                if (!assetPath.startsWith("public/")) {
                    assetPath = "public/" + assetPath;
                }
                try {
                    Context ctx = getContext().getApplicationContext();
                    AssetManager am = ctx.getResources().getAssets();
                    AssetFileDescriptor assetFileDescriptor = am.openFd(assetPath);
                    AudioAsset asset = new AudioAsset(this, audioId, assetFileDescriptor, audioChannelNum, volume);
                    asset.setCompletionListener(this::dispatchComplete);
                    audioAssetList.put(audioId, asset);
                    call.resolve(status);
                } catch (IOException e) {
                    Log.e("AudioPlugin", "Error opening asset: " + assetPath, e);
                    call.reject(ERROR_ASSET_PATH_MISSING + " - " + assetPath);
                }
            }
        } catch (Exception ex) {
            Log.e("AudioPlugin", "Error in preloadAsset", ex);
            call.reject("Error in preloadAsset: " + ex.getMessage());
        }
    }

    private void playOrLoop(String action, final PluginCall call) {
        try {
            final String audioId = call.getString(ASSET_ID);
            final Double time = call.getDouble("time", 0.0);
            Log.d(TAG, "Playing asset: " + audioId + ", action: " + action + ", assets count: " + audioAssetList.size());

            if (audioAssetList.containsKey(audioId)) {
                AudioAsset asset = audioAssetList.get(audioId);
                Log.d(TAG, "Found asset: " + audioId + ", type: " + asset.getClass().getSimpleName());

                if (asset != null) {
                    if (LOOP.equals(action)) {
                        asset.loop();
                    } else {
                        if (fadeMusic) {
                            asset.playWithFade(time);
                        } else {
                            asset.play(time);
                        }
                    }
                    call.resolve();
                } else {
                    call.reject("Asset is null: " + audioId);
                }
            } else {
                call.reject("Asset not found: " + audioId);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error in playOrLoop", ex);
            call.reject(ex.getMessage());
        }
    }

    private void initSoundPool() {
        if (audioAssetList == null) {
            audioAssetList = new HashMap<>();
        }

        if (resumeList == null) {
            resumeList = new ArrayList<>();
        }
    }

    private boolean isStringValid(String value) {
        return (value != null && !value.isEmpty() && !value.equals("null"));
    }

    private void stopAudio(String audioId) throws Exception {
        if (!audioAssetList.containsKey(audioId)) {
            throw new Exception(ERROR_ASSET_NOT_LOADED);
        }

        AudioAsset asset = audioAssetList.get(audioId);
        if (asset != null) {
            if (fadeMusic) {
                asset.stopWithFade();
            } else {
                asset.stop();
            }
        }
    }

    private void saveDurationCall(String audioId, PluginCall call) {
        Log.d(TAG, "Saving duration call for later: " + audioId);
        pendingDurationCalls.put(audioId, call);
    }

    public void notifyDurationAvailable(String assetId, double duration) {
        Log.d(TAG, "Duration available for " + assetId + ": " + duration);
        PluginCall savedCall = pendingDurationCalls.remove(assetId);
        if (savedCall != null) {
            JSObject ret = new JSObject();
            ret.put("duration", duration);
            savedCall.resolve(ret);
        }
    }
}
