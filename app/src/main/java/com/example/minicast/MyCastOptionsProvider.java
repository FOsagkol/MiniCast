// app/src/main/java/com/example/minicast/MyCastOptionsProvider.java
package com.example.minicast;

import android.content.Context;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import java.util.List;

public class MyCastOptionsProvider implements OptionsProvider {
  @Override
  public CastOptions getCastOptions(Context context) {
    // Şimdilik basit: boş alıcı ID (receiverId) olmadan default
    return new CastOptions.Builder().build();
  }
  @Override
  public List<SessionProvider> getAdditionalSessionProviders(Context context) { return null; }
}
