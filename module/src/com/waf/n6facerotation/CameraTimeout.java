package com.waf.n6facerotation;

import android.os.Handler;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** One instance per native SmartRotationHandler; callbacks run on that handler. */
public final class CameraTimeout {
    private final Handler handler;
    private final Field cameraOn;
    private final Field ahead;
    private final Field waiting;
    private final Field faceAngle;
    private final Method releaseCamera;
    private final Runnable timeout = new Runnable() {
        @Override public void run() {
            try {
                if (cameraOn.getBoolean(null) && ahead.getBoolean(null) && !waiting.getBoolean(null)) {
                    ahead.setBoolean(null, false);
                    faceAngle.setInt(null, -2);
                    releaseCamera.invoke(null);
                    log("Pre-open face timeout: requested native camera stop", null);
                }
            } catch (Throwable error) {
                log("Pre-open face timeout failed", error);
            }
        }
    };

    public CameraTimeout(Handler handler, Class<?> rotation) throws ReflectiveOperationException {
        this.handler = handler;
        cameraOn = rotation.getDeclaredField("sIsCameraOn");
        ahead = rotation.getDeclaredField("sLaunchCameraAhead");
        waiting = rotation.getDeclaredField("sIsWaitingFaceAngle");
        faceAngle = rotation.getDeclaredField("sFaceAngle");
        cameraOn.setAccessible(true);
        ahead.setAccessible(true);
        waiting.setAccessible(true);
        faceAngle.setAccessible(true);
        releaseCamera = rotation.getMethod("releaseCamera");
    }

    public void onMessageHandled(int what) {
        try {
            if (what == 10005) {
                handler.removeCallbacks(timeout);
                if (!handler.postDelayed(timeout, 1000L)) log("Pre-open timeout scheduling rejected", null);
            } else if (what == 10006 || what == 10004) {
                handler.removeCallbacks(timeout);
            }
        } catch (Throwable error) {
            log("Pre-open timeout scheduling failed", error);
        }
    }

    private static void log(String message, Throwable error) {
        try {
            if (error == null) Log.i("N6FaceRotation", message);
            else Log.i("N6FaceRotation", message, error);
        } catch (Throwable ignored) {}
    }
}
