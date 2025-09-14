package com.example.minicast;

import android.content.Context;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import java.util.List;

public class MyCastOptionsProvider implements OptionsProvider {
  @Override public CastOptions getCastOptions(Context context) {
    // Default Media Receiver
    return new CastOptions.Builder().build();
  }
  @Override public List<SessionProvider> getAdditionalSessionProviders(Context context) {
    return null;
  }
}
