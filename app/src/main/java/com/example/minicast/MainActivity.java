package com.example.minicast;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    TextView tv = new TextView(this);
    tv.setText("Hello MiniCast!");
    tv.setTextSize(20);
    tv.setGravity(Gravity.CENTER);

    setContentView(tv);
  }
}
