package com.iund.football;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

/** Launcher screen: the broadcast running full screen, with one button to set it as the live wallpaper. */
public class MainActivity extends Activity {
    private GLSurfaceView view;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        view.setRenderer(new Broadcast(this));
        Button set = new Button(this);
        set.setText(R.string.set_wallpaper);
        set.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setWallpaper(); }
        });
        FrameLayout root = new FrameLayout(this);
        root.addView(view);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = (int) (48 * getResources().getDisplayMetrics().density);
        root.addView(set, lp);
        setContentView(root);
    }

    private void setWallpaper() {
        // Literal action strings: CHANGE_LIVE_WALLPAPER is API 16; Honeycomb only has the chooser.
        Intent chooser = new Intent("android.service.wallpaper.LIVE_WALLPAPER_CHOOSER");
        if (Build.VERSION.SDK_INT >= 16) {
            Intent i = new Intent("android.service.wallpaper.CHANGE_LIVE_WALLPAPER");
            i.putExtra("android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT", new ComponentName(this, FootballWallpaperService.class));
            try { startActivity(i); return; } catch (ActivityNotFoundException ignored) { }
        }
        startActivity(chooser);
    }

    @Override protected void onPause() { super.onPause(); view.onPause(); }

    @Override protected void onResume() { super.onResume(); view.onResume(); }
}
