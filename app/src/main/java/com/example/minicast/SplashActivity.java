package com.example.minicast;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    // Varsayılan: 1500 ms bekleme; üst sınır: 10000 ms
    private static final long DEFAULT_HOLD_MS = 1500L;
    private static final long MAX_HOLD_MS = 10_000L;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Ana tema (arkaplan layout'ta); sistem splash kullanmıyoruz burada.
        setTheme(R.style.Theme_MiniCast);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        ImageView logo = findViewById(R.id.splashLogo);

        // Fade-in
        logo.setAlpha(0f);
        logo.animate()
            .alpha(1f)
            .setDuration(1500L)
            .withEndAction(() -> {
                // 1500–10000 ms arası bekleme (Intent extra ile değiştirilebilir)
                long hold = getIntent().getLongExtra("splash_hold_ms", DEFAULT_HOLD_MS);
                hold = Math.min(Math.max(hold, 1500L), MAX_HOLD_MS);

                logo.postDelayed(() -> {
                    // Fade-out
                    logo.animate()
                        .alpha(0f)
                        .setDuration(600L)
                        .withEndAction(() -> {
                            startActivity(new Intent(SplashActivity.this, MainActivity.class));
                            finish();
                        })
                        .start();
                }, hold);
            })
            .start();
    }
}
