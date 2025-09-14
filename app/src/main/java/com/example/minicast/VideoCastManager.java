package com.example.minicast;

import android.os.Bundle;
import android.view.Menu;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.cast_menu, menu);
        // Menüdeki Cast butonunu hazırla
        VideoCastManager.getInstance(this).setUpMediaRouteButton(menu, R.id.media_route_menu_item);
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        VideoCastManager.getInstance(this).release();
    }
}
