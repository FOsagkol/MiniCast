package com.example.minicast;

import android.content.Context;

import com.google.android.gms.cast.CastMediaOptions;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;

import java.util.List;

public class MyCastOptionsProvider implements OptionsProvider {

    @Override
    public CastOptions getCastOptions(Context context) {
        // Basit medya seçenekleri
        CastMediaOptions mediaOptions = new CastMediaOptions.Builder().build();

        // Default Media Receiver (YouTube’un kullandığı değil; Google’ın default alıcısı)
        // ID: "CC1AD845"
        return new CastOptions.Builder()
                .setReceiverApplicationId("CC1AD845")
                .setCastMediaOptions(mediaOptions)
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(Context context) {
        return null; // Ek oturum sağlayıcı yok
    }
}
