package com.example.minicast;  // adjust package name as needed

import android.content.Context;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import java.util.List;

/** Provides Cast configuration options for the Cast SDK. */
public class MyCastOptionsProvider implements OptionsProvider {

    @Override
    public CastOptions getCastOptions(Context context) {
        // Replace R.string.app_id with your receiver application ID (in strings.xml)
        return new CastOptions.Builder()
                .setReceiverApplicationId(context.getString(R.string.app_id))
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(Context context) {
        // Return null if not using additional session providers
        return null;
    }
}        return null;
    }
}
