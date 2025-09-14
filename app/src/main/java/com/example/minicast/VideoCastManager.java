package com.example.minicast;

import android.content.Context;
import android.view.Menu;
import android.view.MenuInflater;

import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;

public class VideoCastManager {

    private final Context context;
    private CastContext castContext;
    private CastSession castSession;
    private final SessionManagerListener<CastSession> sessionManagerListener =
            new SessionManagerListener<CastSession>() {
                @Override
                public void onSessionStarted(CastSession session, String sessionId) {
                    castSession = session;
                }

                @Override
                public void onSessionEnded(CastSession session, int error) {
                    if (castSession == session) {
                        castSession = null;
                    }
                }

                // Other callbacks can remain empty
                @Override public void onSessionStarting(CastSession session) {}
                @Override public void onSessionStartFailed(CastSession session, int error) {}
                @Override public void onSessionEnding(CastSession session) {}
                @Override public void onSessionResuming(CastSession session, String s) {}
                @Override public void onSessionResumed(CastSession session, boolean b) {}
                @Override public void onSessionResumeFailed(CastSession session, int error) {}
                @Override public void onSessionSuspended(CastSession session, int reason) {}
            };

    public VideoCastManager(Context context) {
        this.context = context;
        this.castContext = CastContext.getSharedInstance(context);
        this.castContext.getSessionManager().addSessionManagerListener(sessionManagerListener, CastSession.class);
    }

    public void addCastButton(Menu menu, MenuInflater inflater, int menuResourceId, int buttonId) {
        inflater.inflate(menuResourceId, menu);
        MediaRouteButton mediaRouteButton = (MediaRouteButton) menu.findItem(buttonId).getActionView();
        CastButtonFactory.setUpMediaRouteButton(context, mediaRouteButton);
    }

    public void release() {
        if (castContext != null) {
            castContext.getSessionManager().removeSessionManagerListener(sessionManagerListener, CastSession.class);
        }
    }

    public CastSession getCastSession() {
        return castSession;
    }
}
