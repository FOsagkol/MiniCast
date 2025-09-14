package com.example.minicast;

import android.content.Context;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import java.util.List;

/** Provides Cast configuration options for the Cast SDK. */
public class MyCastOptionsProvider implements OptionsProvider {

    @Override
    public CastOptions getCastOptions(Context context) {
        // R.string.app_id yerine, strings.xml içinde tanımlı uygulama ID değerini kullanın.
        return new CastOptions.Builder()
                .setReceiverApplicationId(context.getString(R.string.app_id))
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(Context context) {
        // Ek oturum sağlayıcısı kullanılmıyorsa null dönülür
        return null;
    }
}
