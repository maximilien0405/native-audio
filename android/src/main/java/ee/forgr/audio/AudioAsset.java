package ee.forgr.audio;

import android.content.res.AssetFileDescriptor;
import android.os.Build;
import androidx.annotation.RequiresApi;
import java.util.ArrayList;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class AudioAsset {

    private final String TAG = "AudioAsset";

    private final ArrayList<AudioDispatcher> audioList;
    private int playIndex = 0;
    protected final NativeAudio owner;
    protected AudioCompletionListener completionListener;
    protected String assetId;
    private Handler currentTimeHandler;
    private Runnable currentTimeRunnable;
    private static final float FADE_STEP = 0.05f;
    private static final int FADE_DELAY_MS = 80;
    private float initialVolume;

    AudioAsset(NativeAudio owner, String assetId, AssetFileDescriptor assetFileDescriptor, int audioChannelNum, float volume)
        throws Exception {
        audioList = new ArrayList<>();
        this.owner = owner;
        this.assetId = assetId;
        this.initialVolume = volume;

        if (audioChannelNum < 0) {
            audioChannelNum = 1;
        }

        for (int x = 0; x < audioChannelNum; x++) {
            AudioDispatcher audioDispatcher = new AudioDispatcher(assetFileDescriptor, volume);
            audioList.add(audioDispatcher);
            if (audioChannelNum == 1) audioDispatcher.setOwner(this);
        }
    }

    public void dispatchComplete() {
        this.owner.dispatchComplete(this.assetId);
    }

    public void play(Double time) throws Exception {
        AudioDispatcher audio = audioList.get(playIndex);
        if (audio != null) {
            audio.play(time);
            playIndex++;
            playIndex = playIndex % audioList.size();
            Log.d(TAG, "Starting timer from play");  // Debug log
            startCurrentTimeUpdates();  // Make sure this is called
        } else {
            throw new Exception("AudioDispatcher is null");
        }
    }

    public double getDuration() {
        if (audioList.size() != 1) return 0;

        AudioDispatcher audio = audioList.get(playIndex);

        if (audio != null) {
            return audio.getDuration();
        }
        return 0;
    }

    public void setCurrentPosition(double time) {
        if (audioList.size() != 1) return;

        AudioDispatcher audio = audioList.get(playIndex);

        if (audio != null) {
            audio.setCurrentPosition(time);
        }
    }

    public double getCurrentPosition() {
        if (audioList.size() != 1) return 0;

        AudioDispatcher audio = audioList.get(playIndex);

        if (audio != null) {
            return audio.getCurrentPosition();
        }
        return 0;
    }

    public boolean pause() throws Exception {
        stopCurrentTimeUpdates();  // Stop updates when pausing
        boolean wasPlaying = false;

        for (int x = 0; x < audioList.size(); x++) {
            AudioDispatcher audio = audioList.get(x);
            wasPlaying |= audio.pause();
        }

        return wasPlaying;
    }

    public void resume() throws Exception {
        if (!audioList.isEmpty()) {
            AudioDispatcher audio = audioList.get(0);
            if (audio != null) {
                audio.resume();
                Log.d(TAG, "Starting timer from resume");  // Debug log
                startCurrentTimeUpdates();  // Make sure this is called
            } else {
                throw new Exception("AudioDispatcher is null");
            }
        }
    }

    public void stop() throws Exception {
        stopCurrentTimeUpdates();  // Stop updates when stopping
        for (int x = 0; x < audioList.size(); x++) {
            AudioDispatcher audio = audioList.get(x);

            if (audio != null) {
                audio.stop();
            } else {
                throw new Exception("AudioDispatcher is null");
            }
        }
    }

    public void loop() throws Exception {
        AudioDispatcher audio = audioList.get(playIndex);
        if (audio != null) {
            audio.loop();
            playIndex++;
            playIndex = playIndex % audioList.size();
            startCurrentTimeUpdates(); // Add timer start
        } else {
            throw new Exception("AudioDispatcher is null");
        }
    }

    public void unload() throws Exception {
        this.stop();

        for (int x = 0; x < audioList.size(); x++) {
            AudioDispatcher audio = audioList.get(x);

            if (audio != null) {
                audio.unload();
            } else {
                throw new Exception("AudioDispatcher is null");
            }
        }

        audioList.clear();
    }

    public void setVolume(float volume) throws Exception {
        for (int x = 0; x < audioList.size(); x++) {
            AudioDispatcher audio = audioList.get(x);

            if (audio != null) {
                audio.setVolume(volume);
            } else {
                throw new Exception("AudioDispatcher is null");
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public void setRate(float rate) throws Exception {
        for (int x = 0; x < audioList.size(); x++) {
            AudioDispatcher audio = audioList.get(x);
            if (audio != null) {
                audio.setRate(rate);
            }
        }
    }

    public boolean isPlaying() throws Exception {
        if (audioList.size() != 1) return false;

        return audioList.get(playIndex).isPlaying();
    }

    public void setCompletionListener(AudioCompletionListener listener) {
        this.completionListener = listener;
    }

    protected void notifyCompletion() {
        if (completionListener != null) {
            completionListener.onCompletion(this.assetId);
        }
    }

    protected String getAssetId() {
        return assetId;
    }

    public void setCurrentTime(double time) throws Exception {
        owner.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (audioList.size() != 1) {
                    return;
                }
                AudioDispatcher audio = audioList.get(playIndex);
                if (audio != null) {
                    audio.setCurrentPosition(time);
                }
            }
        });
    }

    protected void startCurrentTimeUpdates() {
        Log.d(TAG, "Starting timer updates in AudioAsset");
        if (currentTimeHandler == null) {
            currentTimeHandler = new Handler(Looper.getMainLooper());
        }
        
        // Add small delay to let audio start playing
        currentTimeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startTimeUpdateLoop();
            }
        }, 100);  // 100ms delay
    }

    private void startTimeUpdateLoop() {
        currentTimeRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    AudioDispatcher audio = audioList.get(playIndex);
                    if (audio != null && audio.isPlaying()) {
                        double currentTime = getCurrentPosition();
                        Log.d(TAG, "Timer update: currentTime = " + currentTime);
                        owner.notifyCurrentTime(assetId, currentTime);
                        currentTimeHandler.postDelayed(this, 100);
                    } else {
                        Log.d(TAG, "Stopping timer - not playing");
                        stopCurrentTimeUpdates();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error getting current time", e);
                    stopCurrentTimeUpdates();
                }
            }
        };
        currentTimeHandler.post(currentTimeRunnable);
    }

    void stopCurrentTimeUpdates() {
        Log.d(TAG, "Stopping timer updates in AudioAsset");
        if (currentTimeHandler != null && currentTimeRunnable != null) {
            currentTimeHandler.removeCallbacks(currentTimeRunnable);
        }
    }

    public void playWithFade(Double time) throws Exception {
        AudioDispatcher audio = audioList.get(playIndex);
        if (audio != null) {
            audio.setVolume(0);
            audio.play(time);
            fadeIn(audio);
            startCurrentTimeUpdates();
        }
    }

    private void fadeIn(final AudioDispatcher audio) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable fadeRunnable = new Runnable() {
            float currentVolume = 0;
            @Override
            public void run() {
                if (currentVolume < initialVolume) {
                    currentVolume += FADE_STEP;
                    try {
                        audio.setVolume(currentVolume);
                        handler.postDelayed(this, FADE_DELAY_MS);
                    } catch (Exception e) {
                        Log.e(TAG, "Error during fade in", e);
                    }
                }
            }
        };
        handler.post(fadeRunnable);
    }

    public void stopWithFade() throws Exception {
        AudioDispatcher audio = audioList.get(playIndex);
        if (audio != null && audio.isPlaying()) {
            fadeOut(audio);
        }
    }

    private void fadeOut(final AudioDispatcher audio) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable fadeRunnable = new Runnable() {
            float currentVolume = initialVolume;
            @Override
            public void run() {
                if (currentVolume > FADE_STEP) {
                    currentVolume -= FADE_STEP;
                    try {
                        audio.setVolume(currentVolume);
                        handler.postDelayed(this, FADE_DELAY_MS);
                    } catch (Exception e) {
                        Log.e(TAG, "Error during fade out", e);
                    }
                } else {
                    try {
                        audio.setVolume(0);
                        stop();
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping after fade", e);
                    }
                }
            }
        };
        handler.post(fadeRunnable);
    }
}
