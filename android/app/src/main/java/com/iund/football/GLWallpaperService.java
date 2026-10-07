package com.iund.football;

import android.opengl.GLSurfaceView;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL10;

/**
 * A WallpaperService that renders with OpenGL ES 2.0 on its own thread (GLSurfaceView needs a View hierarchy;
 * a wallpaper only has a raw SurfaceHolder). Adapted from iund/spreadpoint-clock-screensaver's Android port:
 * the window surface is released while invisible but the context is kept, so textures survive a trip away
 * from the home screen. Takes a plain GLSurfaceView.Renderer so MainActivity's preview reuses the same renderer.
 */
public abstract class GLWallpaperService extends WallpaperService {

    public abstract class GLEngine extends Engine {
        private GLThread thread;

        /** Call from onCreate(). */
        public void setRenderer(GLSurfaceView.Renderer renderer) {
            thread = new GLThread(renderer, getSurfaceHolder());
            thread.start();
        }

        @Override public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (thread != null) thread.setVisible(visible);
        }

        @Override public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            if (thread != null) thread.setSurface(true, 0, 0);
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (thread != null) thread.setSurface(true, width, height);
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            if (thread != null) thread.setSurface(false, 0, 0);
        }

        @Override public void onDestroy() {
            super.onDestroy();
            if (thread != null) thread.exitAndWait();
        }
    }

    static final class GLThread extends Thread {
        // Not in the EGL10 binding (it predates ES 2), declared as GLSurfaceView does internally.
        private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098, EGL_RENDERABLE_TYPE = 0x3040, EGL_OPENGL_ES2_BIT = 4;
        // No Choreographer before API 16: pace to ~60 fps ourselves; eglSwapBuffers blocks on vsync anyway.
        private static final long FRAME_MILLIS = 16;

        private final GLSurfaceView.Renderer renderer;
        private final SurfaceHolder holder;
        private final Object lock = new Object();
        private boolean visible, surface, sizeChanged, exit;
        private int width, height;

        private EGL10 egl;
        private EGLDisplay display;
        private EGLConfig config;
        private EGLContext context;
        private EGLSurface eglSurface;
        private GL10 gl;

        GLThread(GLSurfaceView.Renderer renderer, SurfaceHolder holder) {
            super("FootballGLThread");
            this.renderer = renderer;
            this.holder = holder;
        }

        void setVisible(boolean v) { synchronized (lock) { visible = v; lock.notifyAll(); } }

        void setSurface(boolean exists, int w, int h) {
            synchronized (lock) {
                surface = exists;
                if (w > 0) { width = w; height = h; sizeChanged = true; }
                lock.notifyAll();
            }
        }

        void exitAndWait() {
            synchronized (lock) { exit = true; lock.notifyAll(); }
            try { join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        @Override public void run() {
            try {
                while (true) {
                    synchronized (lock) {
                        while (!exit && (!visible || !surface)) {
                            destroySurface();
                            try { lock.wait(); } catch (InterruptedException ignored) { }
                        }
                        if (exit) return;
                    }
                    boolean newContext = false;
                    if (display == null) { initEgl(); newContext = true; }
                    if (eglSurface == null) {
                        if (!createSurface()) { sleep(100); continue; }
                        if (newContext) renderer.onSurfaceCreated(gl, config);
                        sizeChanged = true;
                    }
                    int w, h; boolean changed;
                    synchronized (lock) { changed = sizeChanged; sizeChanged = false; w = width; h = height; }
                    if (changed && w > 0 && h > 0) renderer.onSurfaceChanged(gl, w, h);
                    long start = System.nanoTime();
                    renderer.onDrawFrame(gl);
                    if (!egl.eglSwapBuffers(display, eglSurface)) { destroySurface(); sleep(100); continue; }
                    sleep(FRAME_MILLIS - (System.nanoTime() - start) / 1000000L);
                }
            } finally {
                destroySurface();
                if (display != null) {
                    if (context != null) egl.eglDestroyContext(display, context);
                    egl.eglTerminate(display);
                }
            }
        }

        private static void sleep(long ms) {
            if (ms <= 0) return;
            try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
        }

        private void initEgl() {
            egl = (EGL10) EGLContext.getEGL();
            display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            egl.eglInitialize(display, new int[2]);
            int[] spec = {EGL10.EGL_RED_SIZE, 5, EGL10.EGL_GREEN_SIZE, 6, EGL10.EGL_BLUE_SIZE, 5, EGL10.EGL_DEPTH_SIZE, 0,
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] n = new int[1];
            if (!egl.eglChooseConfig(display, spec, configs, 1, n) || n[0] <= 0) throw new RuntimeException("No GLES2 EGL config");
            config = configs[0];
            context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, new int[]{EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE});
        }

        private boolean createSurface() {
            try { eglSurface = egl.eglCreateWindowSurface(display, config, holder, null); } catch (Exception e) { eglSurface = null; }
            if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) { eglSurface = null; return false; }
            if (!egl.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                egl.eglDestroySurface(display, eglSurface);
                eglSurface = null;
                return false;
            }
            gl = (GL10) context.getGL();
            return true;
        }

        private void destroySurface() {
            if (eglSurface == null) return;
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            egl.eglDestroySurface(display, eglSurface);
            eglSurface = null;
        }
    }
}
