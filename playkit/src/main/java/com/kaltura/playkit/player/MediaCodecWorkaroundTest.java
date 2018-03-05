package com.kaltura.playkit.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.ExoPlayerLibraryInfo;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.drm.ExoMediaDrm;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.drm.MediaDrmCallback;
import com.google.android.exoplayer2.drm.UnsupportedDrmException;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.kaltura.playkit.PKDeviceCapabilities;

import org.json.JSONObject;

import java.util.UUID;

public class MediaCodecWorkaroundTest {

    private static final String TAG = "MediaCodecWorkaroundTes";

    private static final String PREFS_ENTRY_FINGERPRINT_DUMMYSURFACE_WORKAROUND = "Build.FINGERPRINT.DummySurface";
    public static boolean workaroundRequired;
    private static final String URL = "asset:///DRMTest/index.mpd";
    private static boolean dummySurfaceWorkaroundRequiredReportSent;

    private static MediaDrmCallback fakeDrmCallback = new MediaDrmCallback() {
        @Override
        public byte[] executeProvisionRequest(UUID uuid, ExoMediaDrm.ProvisionRequest request) throws Exception {
            return null;
        }

        @Override
        public byte[] executeKeyRequest(UUID uuid, ExoMediaDrm.KeyRequest request) throws Exception {
            Thread.sleep(10000);
            return null;
        }
    };

    static void executeTest(final Context context) {

        DataSource.Factory mediaDataSourceFactory = new DefaultDataSourceFactory(context, "whatever");

        Handler mainHandler = new Handler(Looper.getMainLooper());

        DefaultTrackSelector trackSelector = new DefaultTrackSelector(new AdaptiveTrackSelection.Factory(null));


        DefaultDrmSessionManager<FrameworkMediaCrypto> drmSessionManager =
                getDrmSessionManager(mainHandler);

        if (drmSessionManager == null) {
            return;
        }

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context, drmSessionManager);
        final SimpleExoPlayer player = ExoPlayerFactory.newSimpleInstance(renderersFactory, trackSelector);
        player.addListener(new Player.DefaultEventListener() {
            @Override
            public void onPlayerError(ExoPlaybackException error) {
                if (error.getCause() instanceof MediaCodecRenderer.DecoderInitializationException) {
                    workaroundRequired(context, true);
                }
                player.release();
            }

            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    // If we receive player state ready, we can assume that no workaround required.
                    // So set the workaroundRequired flag to false.
                    workaroundRequired(context, false);
                    player.release();
                }
            }
        });

        MediaSource mediaSource = new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                mediaDataSourceFactory)
                .createMediaSource(Uri.parse(URL), mainHandler, null);

        player.prepare(mediaSource);
    }

    private static void workaroundRequired(Context context, boolean b) {
        workaroundRequired = b;
        if (b) {
            maybeSendReport(context);
        }
    }

    private static DefaultDrmSessionManager<FrameworkMediaCrypto> getDrmSessionManager(Handler mainHandler) {
        try {
            return DefaultDrmSessionManager.newWidevineInstance(fakeDrmCallback, null, mainHandler, null);
        } catch (UnsupportedDrmException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static void maybeSendReport(final Context context) {
        if (dummySurfaceWorkaroundRequiredReportSent) {
            return;
        }

        final SharedPreferences sharedPrefs = context.getSharedPreferences(PKDeviceCapabilities.SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        String savedFingerprint = sharedPrefs.getString(PREFS_ENTRY_FINGERPRINT_DUMMYSURFACE_WORKAROUND, null);

        // If we already sent this report for this Android build, don't send again.
        if (Build.FINGERPRINT.equals(savedFingerprint)) {
            dummySurfaceWorkaroundRequiredReportSent = true;
            return;
        }

        // Do everything else in a thread.
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                String reportString;
                try {
                    JSONObject jsonObject = new JSONObject()
                            .put("reportType", "DummySurfaceWorkaround")
                            .put("system", PKDeviceCapabilities.systemInfo())
                            .put("dummySurfaceWorkaroundRequired", true)
                            .put("exoPlayerVersion", ExoPlayerLibraryInfo.VERSION);

                    reportString = jsonObject.toString();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to get report", e);
                    reportString = PKDeviceCapabilities.getErrorReport(e);
                }

                if (!PKDeviceCapabilities.sendReport(reportString)) return;

                // If we got here, save the fingerprint so we don't send again until the OS updates.
                sharedPrefs.edit().putString(PREFS_ENTRY_FINGERPRINT_DUMMYSURFACE_WORKAROUND, Build.FINGERPRINT).apply();
                dummySurfaceWorkaroundRequiredReportSent = true;
            }
        });
    }
}

