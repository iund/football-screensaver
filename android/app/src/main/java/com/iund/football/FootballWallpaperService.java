package com.iund.football;

import android.view.SurfaceHolder;

/** The live wallpaper: the broadcast renderer bound to the wallpaper surface. No touch handling. */
public class FootballWallpaperService extends GLWallpaperService {
    @Override public Engine onCreateEngine() {
        return new GLEngine() {
            @Override public void onCreate(SurfaceHolder holder) {
                super.onCreate(holder);
                setRenderer(new Broadcast(FootballWallpaperService.this));
            }
        };
    }
}
