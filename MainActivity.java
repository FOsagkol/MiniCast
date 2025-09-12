package com.example.minicast;

import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.browser.customtabs.CustomTabsIntent;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;

public class MainActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // Cast framework
        CastContext.getSharedInstance(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Button open = findViewById(R.id.openChrome);
        open.setOnClickListener(v -> {
            String url = "https://www.google.com";
            CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
            CustomTabsIntent intent = builder.build();
            intent.launchUrl(this, Uri.parse(url));
        });
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        CastButtonFactory.setUpMediaRouteButton(getApplicationContext(),
												menu, R.id.media_route_menu_item);
        return true;
    }
}
