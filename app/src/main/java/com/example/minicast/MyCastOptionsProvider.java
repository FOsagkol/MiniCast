package com.example.minicast;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;

import java.util.Collections;
import java.util.List;

public class MyCastOptionsProvider implements OptionsProvider {

    @Override
    @NonNull
    public CastOptions getCastOptions(@NonNull Context appContext) {
        return new CastOptions.Builder()
                .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
                .build();
    }

    @Override
    @NonNull
    public List<SessionProvider> getAdditionalSessionProviders(@NonNull Context appContext) {
        return Collections.emptyList(); // null yerine boş liste
    }
}
