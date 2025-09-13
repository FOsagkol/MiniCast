package com.example.minicast;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.MediaLoadRequestData;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

public class MainActivity extends AppCompatActivity {

    private CastContext castContext;
    private SessionManager sessionManager;
    private CastSession currentSession;

    // Optional: if app was launched via Share → receive URL
    private String sharedWebVideoUrl = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Initialize Cast framework
        castContext = CastContext.getSharedInstance(this);
        sessionManager = castContext.getSessionManager();

        // If launched from Android Share menu with a URL
        Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null && sharedText.startsWith("http")) {
                sharedWebVideoUrl = sharedText.trim();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        currentSession = sessionManager.getCurrentCastSession();
        sessionManager.addSessionManagerListener(sessionListener, CastSession.class);

        // If already connected when resuming, try to load media immediately
        if (currentSession != null && currentSession.isConnected()) {
            loadSampleOrSharedVideo();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sessionManager != null) {
            sessionManager.removeSessionManagerListener(sessionListener, CastSession.class);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.cast_menu, menu);
        CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.media_route_menu_item);
        return true;
    }

    private final SessionManagerListener<CastSession> sessionListener =
            new SessionManagerListener<CastSession>() {
                @Override
                public void onSessionStarting(CastSession session) { }

                @Override
                public void onSessionStarted(CastSession session, String sessionId) {
                    currentSession = session;
                    loadSampleOrSharedVideo();
                }

                @Override
                public void onSessionStartFailed(CastSession session, int error) { }

                @Override
                public void onSessionEnding(CastSession session) { }

                @Override
                public void onSessionEnded(CastSession session, int error) {
                    if (currentSession == session) currentSession = null;
                }

                @Override
                public void onSessionResuming(CastSession session, String sessionId) { }

                @Override
                public void onSessionResumed(CastSession session, boolean wasSuspended) {
                    currentSession = session;
                    loadSampleOrSharedVideo();
                }

                @Override
                public void onSessionResumeFailed(CastSession session, int error) { }

                @Override
                public void onSessionSuspended(CastSession session, int reason) { }
            };

    private void loadSampleOrSharedVideo() {
        if (currentSession == null) return;

        RemoteMediaClient remote = currentSession.getRemoteMediaClient();
        if (remote == null) return;

        // Use a real MP4 for guaranteed playback on Chromecast
        // If a URL was shared into the app, use that instead
        String videoUrl = (sharedWebVideoUrl != null) ? sharedWebVideoUrl
                : "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4";
        String title = (sharedWebVideoUrl != null) ? "Web Video" : "Big Buck Bunny";

        MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        md.putString(MediaMetadata.KEY_TITLE, title);

        MediaInfo mediaInfo = new MediaInfo.Builder(videoUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType("video/mp4") // if your URL isn't mp4, adjust contentType
                .setMetadata(md)
                .build();

        MediaLoadRequestData requestData = new MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .build();

        remote.load(requestData);
    }
}
