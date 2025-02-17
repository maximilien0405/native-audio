package ee.forgr.audio;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class RemoteAudioAsset extends AudioAsset {

    private static final String TAG = "RemoteAudioAsset";
    private final ArrayList<ExoPlayer> players;
    private int playIndex = 0;
    private final Uri uri;
    private float volume;
    private boolean isPrepared = false;
    private static SimpleCache cache;
    private static final long MAX_CACHE_SIZE = 100 * 1024 * 1024; // 100MB cache
    protected AudioCompletionListener completionListener;

    public RemoteAudioAsset(NativeAudio owner, String assetId, Uri uri, int audioChannelNum, float volume) throws Exception {
        super(owner, assetId, null, 0, volume);
        this.uri = uri;
        this.volume = volume;
        this.players = new ArrayList<>();

        if (audioChannelNum < 1) {
            audioChannelNum = 1;
        }

        for (int i = 0; i < audioChannelNum; i++) {
            ExoPlayer player = new ExoPlayer.Builder(owner.getContext()).build();
            players.add(player);
            initializePlayer(player);
        }
    }

    private void initializePlayer(ExoPlayer player) {
        // Initialize cache if not already done
        if (cache == null) {
            File cacheDir = new File(owner.getContext().getCacheDir(), "media");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            cache = new SimpleCache(
                cacheDir,
                new LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE),
                new StandaloneDatabaseProvider(owner.getContext())
            );
        }

        // Create cached data source factory
        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory();
        CacheDataSource.Factory cacheDataSourceFactory = new CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        // Handle both HLS and regular audio
        if (uri.toString().endsWith(".m3u8")) {
            HlsMediaSource mediaSource = new HlsMediaSource.Factory(cacheDataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            player.setMediaSource(mediaSource);
        } else {
            player.setMediaItem(MediaItem.fromUri(uri));
        }

        player.setVolume(volume);
        player.prepare();
    }

    @Override
    public void play(Double time) throws Exception {
        if (players.isEmpty()) {
            throw new Exception("No ExoPlayer available");
        }

        ExoPlayer player = players.get(playIndex);
        if (!isPrepared) {
            Log.d(TAG, "ExoPlayer not yet prepared, waiting...");
            player.addListener(
                new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int playbackState) {
                        if (playbackState == Player.STATE_READY) {
                            isPrepared = true;
                            try {
                                playInternal(player, time);
                            } catch (Exception e) {
                                Log.e(TAG, "Error playing after prepare", e);
                            }
                        } else if (playbackState == Player.STATE_ENDED) {
                            owner.dispatchComplete(getAssetId());
                            notifyCompletion();
                        }
                    }
                }
            );
        } else {
            playInternal(player, time);
        }

        playIndex = (playIndex + 1) % players.size();
    }

    private void playInternal(ExoPlayer player, Double time) throws Exception {
        if (time != null) {
            player.seekTo(Math.round(time * 1000));
        }
        player.play();
    }

    @Override
    public boolean pause() throws Exception {
        boolean wasPlaying = false;
        for (ExoPlayer player : players) {
            if (player.isPlaying()) {
                player.pause();
                wasPlaying = true;
            }
        }
        return wasPlaying;
    }

    @Override
    public void resume() throws Exception {
        for (ExoPlayer player : players) {
            if (!player.isPlaying()) {
                player.play();
            }
        }
    }

    @Override
    public void stop() throws Exception {
        for (ExoPlayer player : players) {
            if (player.isPlaying()) {
                player.stop();
            }
            // Reset the ExoPlayer to make it ready for future playback
            initializePlayer(player);
        }
        isPrepared = false;
    }

    @Override
    public void loop() throws Exception {
        if (!players.isEmpty()) {
            ExoPlayer player = players.get(playIndex);
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
            player.play();
            playIndex = (playIndex + 1) % players.size();
        }
    }

    @Override
    public void unload() throws Exception {
        for (ExoPlayer player : players) {
            player.release();
        }
        players.clear();
    }

    @Override
    public void setVolume(float volume) throws Exception {
        this.volume = volume;
        for (ExoPlayer player : players) {
            player.setVolume(volume);
        }
    }

    @Override
    public boolean isPlaying() throws Exception {
        for (ExoPlayer player : players) {
            if (player.isPlaying()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double getDuration() {
        if (!players.isEmpty() && isPrepared) {
            return players.get(0).getDuration() / 1000.0;
        }
        return 0;
    }

    @Override
    public double getCurrentPosition() {
        if (!players.isEmpty() && isPrepared) {
            return players.get(0).getCurrentPosition() / 1000.0;
        }
        return 0;
    }

    public static void clearCache(Context context) {
        try {
            if (cache != null) {
                cache.release();
                cache = null;
            }
            File cacheDir = new File(context.getCacheDir(), "media");
            if (cacheDir.exists()) {
                deleteDir(cacheDir);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing audio cache", e);
        }
    }

    private static boolean deleteDir(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) {
                        return false;
                    }
                }
            }
        }
        return dir.delete();
    }

    public void setCompletionListener(AudioCompletionListener listener) {
        this.completionListener = listener;
    }

    protected void notifyCompletion() {
        if (completionListener != null) {
            completionListener.onCompletion(getAssetId());
        }
    }
}
