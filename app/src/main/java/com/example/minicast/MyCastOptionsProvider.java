package com.example.minicast;

import android.content.Context;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastMediaOptions;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import java.util.List;

public class MyCastOptionsProvider implements OptionsProvider {
    @Override public CastOptions getCastOptions(Context context) {
        CastMediaOptions mediaOptions = new CastMediaOptions.Builder().build();
        return new CastOptions.Builder()
                .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
                .setCastMediaOptions(mediaOptions)
                .build();
    }

    @Override public List<SessionProvider> getAdditionalSessionProviders(Context context) {
        return null;
    }
}
