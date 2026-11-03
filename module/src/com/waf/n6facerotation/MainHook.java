package com.waf.n6facerotation;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "N6FaceRotation";
    private static final int ROTATION = 393218;
    private static final ThreadLocal<Boolean> rotationRequest = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> validAngle = new ThreadLocal<>();
    private static final ThreadLocal<Integer> phoneRollForFrame = new ThreadLocal<>();
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicBoolean maskLogged = new AtomicBoolean();
    private static final AtomicBoolean probing = new AtomicBoolean();
    private static final AtomicBoolean sensorInfoLogged = new AtomicBoolean();
    private static volatile Boolean sensorHubProvidesOrientation;
    private static long lastMissingLog;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if ("android".equals(lp.packageName) && "android".equals(lp.processName)) {
            installSystemServer(lp.classLoader);
            return;
        }
        if (!"com.aiunit.aon".equals(lp.packageName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!installed.compareAndSet(false, true)) return;
                Context context = (Context) param.args[0];
                try {
                    install(context.getClassLoader(), lp.processName);
                    Log.i(TAG, "0.4.12 hooks installed process=" + lp.processName);
                } catch (Throwable error) {
                    Log.i(TAG, "hook installation failed " + error.getClass().getSimpleName());
                }
            }
        });
    }

    private static void installSystemServer(ClassLoader loader) {
        try {
            RotationController.install(loader);
        } catch (Throwable error) {
            Log.i(TAG, "independent rotation hook installation failed", error);
        }
    }

    private static void install(ClassLoader loader, String processName) {
        Class<?> helper = XposedHelpers.findClass("k.c", loader);
        XposedHelpers.findAndHookMethod(helper, "a", HashSet.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setObjectExtra("previousRotation", rotationRequest.get());
                rotationRequest.set(p.args[0] != null && ((HashSet<?>) p.args[0]).contains(ROTATION));
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Boolean previous = (Boolean) p.getObjectExtra("previousRotation");
                if (previous == null) rotationRequest.remove(); else rotationRequest.set(previous);
            }
        });
        XposedHelpers.findAndHookConstructor("k.b", loader, helper, int.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                int mask = (Integer) p.args[1];
                if (Boolean.TRUE.equals(rotationRequest.get()) && (Integer) p.args[2] == 0 && mask != 0) {
                    p.args[1] = mask | 2;
                    if (maskLogged.compareAndSet(false, true)) Log.i(TAG, "rotation mask " + mask + " -> " + (mask | 2));
                }
            }
        });
        Class<?> frameClass = XposedHelpers.findClass("com.aon.service.aonsensorhublib.p", loader);
        XposedHelpers.findAndHookMethod("com.aiunit.aon.operator.OSensorHubResultProcess", loader, "transform", Object.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                validAngle.remove();
                phoneRollForFrame.remove();
                Object frame = p.args[0];
                if (!frameClass.isInstance(frame)) return;
                Object angle = XposedHelpers.getObjectField(frame, "c");
                float roll = angle == null ? Float.NaN : XposedHelpers.getFloatField(angle, "c");
                Object fusion = XposedHelpers.getObjectField(frame, "b");
                int faceCount = fusion == null ? -1 : XposedHelpers.getIntField(fusion, "b");
                float imageRotation = fusion == null ? Float.NaN : XposedHelpers.getFloatField(fusion, "c");
                int phoneRoll = fusion == null ? -1 : XposedHelpers.getIntField(fusion, "d");
                phoneRollForFrame.set(phoneRoll);
                if (sensorInfoLogged.compareAndSet(false, true)) {
                    try {
                        Object engine = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("com.aiunit.aon.engine.e", loader), "a");
                        Object camera = XposedHelpers.callMethod(engine, "x");
                        sensorHubProvidesOrientation = (Boolean) XposedHelpers.callMethod(camera, "canGetSensorInfoFromSensorHub");
                        Log.i(TAG, "sensor hub provides sensor info=" + sensorHubProvidesOrientation);
                    } catch (Throwable error) { Log.i(TAG, "sensor-info query unavailable " + error.getClass().getSimpleName()); }
                }
                boolean valid = !Float.isNaN(roll) && !Float.isInfinite(roll);
                validAngle.set(valid);
                if (valid) Log.i(TAG, "roll=" + roll + " fusionTargets=" + faceCount + " imageRotation=" + imageRotation + " phoneRoll=" + phoneRoll);
                else logMissingAngle(faceCount, imageRotation, phoneRoll);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable()) { validAngle.remove(); phoneRollForFrame.remove(); }
            }
        });
        XposedHelpers.findAndHookMethod("s.a", loader, "I", int.class, ArrayList.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (Boolean.TRUE.equals(validAngle.get())) {
                    ArrayList<?> faces = (ArrayList<?>) p.args[1];
                    ArrayList<Object> withAngle = new ArrayList<>();
                    Integer phoneRoll = phoneRollForFrame.get();
                    boolean canonical = Boolean.TRUE.equals(sensorHubProvidesOrientation) && phoneRoll != null
                            && (phoneRoll == 0 || phoneRoll == 90 || phoneRoll == 180 || phoneRoll == 270);
                    for (Object face : faces) {
                        float[] pose = (float[]) XposedHelpers.getObjectField(face, "i");
                        if (pose == null || pose.length < 3 || Float.isNaN(pose[2]) || Float.isInfinite(pose[2])) continue;
                        if (canonical) {
                            Object relativeFace = XposedHelpers.newInstance(face.getClass());
                            for (Field field : face.getClass().getDeclaredFields()) {
                                if (Modifier.isStatic(field.getModifiers())) continue;
                                field.setAccessible(true);
                                field.set(relativeFace, field.get(face));
                            }
                            // Reuse the shipped head-pose normalization for SensorHub orientation data.
                            float relative = (pose[2] - phoneRoll) % 360f;
                            if (relative < -180f) relative += 360f;
                            else if (relative >= 180f) relative -= 360f;
                            XposedHelpers.setFloatField(relativeFace, "c", relative);
                            withAngle.add(relativeFace);
                            Log.i(TAG, "rotation face raw=" + pose[2] + " phoneRoll=" + phoneRoll + " relative=" + relative);
                        } else withAngle.add(face);
                    }
                    if (!faces.isEmpty() && withAngle.isEmpty()) {
                        XposedHelpers.callMethod(p.thisObject, "h");
                        p.setResult(null);
                    } else if (canonical || withAngle.size() != faces.size()) p.args[1] = withAngle;
                    return;
                }
                if (!Boolean.FALSE.equals(validAngle.get())) return;
                if (((ArrayList<?>) p.args[1]).isEmpty()) return;
                // Any event closes the framework camera; await Angle for an existing face.
                XposedHelpers.callMethod(p.thisObject, "h");
                p.setResult(null);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) { validAngle.remove(); phoneRollForFrame.remove(); }
        });
        XposedHelpers.findAndHookMethod("s.a", loader, "H", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Integer phoneRoll = phoneRollForFrame.get();
                if (!Boolean.TRUE.equals(validAngle.get()) || !Boolean.TRUE.equals(sensorHubProvidesOrientation)
                        || phoneRoll == null || (phoneRoll != 0 && phoneRoll != 90 && phoneRoll != 180 && phoneRoll != 270)) return;
                // The stock state machine folds upside-down faces back to upright.
                // Our independent controller consumes all four relative directions.
                float roll = XposedHelpers.getFloatField(p.thisObject, "l");
                if (roll < 0f) roll += 360f;
                XposedHelpers.setIntField(p.thisObject, "i", (int) roll);
                p.setResult(null);
            }
        });
        if (!"com.aiunit.aon.ui".equals(processName)) return;
        XposedHelpers.findAndHookMethod("com.aiunit.aon.AONApplication", loader, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable()) return;
                Context context = (Context) p.thisObject;
                IntentFilter diagnostics = new IntentFilter("com.waf.n6facerotation.PROBE");
                diagnostics.addAction("com.waf.n6facerotation.REPLAY");
                context.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent intent) {
                        boolean replay = "com.waf.n6facerotation.REPLAY".equals(intent.getAction());
                        if (!replay && !"com.waf.n6facerotation.PROBE".equals(intent.getAction())) return;
                        if (!probing.compareAndSet(false, true)) { Log.i(TAG, "probe already running"); return; }
                        if (replay) {
                            new Thread(() -> {
                                try {
                                    String report = FrameReplay.run(context.getClassLoader());
                                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                                            new java.io.File(context.getCacheDir(), "n6-frame-replay.txt"))) {
                                        output.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                    }
                                    XposedBridge.log("N6FaceRotation " + report);
                                    Log.i(TAG, report.substring(report.lastIndexOf('\n') + 1));
                                }
                                catch (Throwable error) { Log.i(TAG, "frame replay failed", error); }
                                finally { probing.set(false); }
                            }, "N6FaceRotationReplay").start();
                        } else new Thread(() -> runProbe(context), "N6FaceRotationProbe").start();
                    }
                }, diagnostics, "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
                Log.i(TAG, "shell/root probe receiver ready");
            }
        });
    }

    private static synchronized void logMissingAngle(int faceCount, float imageRotation, int phoneRoll) {
        long now = SystemClock.elapsedRealtime();
        if (lastMissingLog == 0 || now - lastMissingLog >= 2000) {
            lastMissingLog = now;
            Log.i(TAG, "frame missing finite Angle roll fusionTargets=" + faceCount + " imageRotation=" + imageRotation + " phoneRoll=" + phoneRoll);
        }
    }

    private static final class ProbeListener extends Binder {
        private static final String DESCRIPTOR = "com.aiunit.aon.utils.IAONEventListener";
        ProbeListener() { attachInterface(null, DESCRIPTOR); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
            if (code == 1 || code == 2) {
                data.enforceInterface(DESCRIPTOR);
                if (code == 1) {
                    int eventType = data.readInt();
                    int angle = data.readInt();
                    Log.i(TAG, "probe event=" + eventType + " angle=" + angle);
                }
                // callback2 carries FaceInfo; this diagnostic never reads or logs it.
                if (reply != null) { reply.writeNoException(); if (code == 2) reply.writeInt(0); }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static int serviceCall(IBinder service, int code, IBinder listener) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.aiunit.aon.utils.IAONService");
            if (code == 5 || code == 6) data.writeStrongBinder(listener);
            data.writeInt(ROTATION);
            if (!service.transact(code, data, reply, 0)) throw new RemoteException("unsupported transaction");
            reply.readException();
            return reply.readInt();
        } finally { reply.recycle(); data.recycle(); }
    }

    private static void runProbe(Context context) {
        CountDownLatch connected = new CountDownLatch(1);
        IBinder[] service = new IBinder[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { service[0] = binder; connected.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { }
            @Override public void onNullBinding(ComponentName name) { connected.countDown(); }
            @Override public void onBindingDied(ComponentName name) { connected.countDown(); }
        };
        ProbeListener listener = new ProbeListener();
        boolean bound = false, registered = false, started = false;
        try {
            Intent intent = new Intent().setComponent(new ComponentName("com.aiunit.aon", "com.aiunit.aon.AONService"));
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound || !connected.await(5, TimeUnit.SECONDS) || service[0] == null) {
                Log.i(TAG, "probe bind unavailable"); return;
            }
            int registration = serviceCall(service[0], 5, listener);
            registered = registration == 0;
            Log.i(TAG, "probe register=" + registration);
            if (!registered) return;
            int start = serviceCall(service[0], 3, null);
            started = start == 0;
            Log.i(TAG, "probe start=" + start);
            if (started) Thread.sleep(5000);
        } catch (Throwable error) {
            Log.i(TAG, "probe failed " + error.getClass().getSimpleName());
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (started) try { Log.i(TAG, "probe stop=" + serviceCall(service[0], 4, null)); }
                catch (Throwable error) { Log.i(TAG, "probe stop failed " + error.getClass().getSimpleName()); }
            if (registered) try { Log.i(TAG, "probe unregister=" + serviceCall(service[0], 6, listener)); }
                catch (Throwable error) { Log.i(TAG, "probe unregister failed " + error.getClass().getSimpleName()); }
            if (bound) try { context.unbindService(connection); }
                catch (Throwable error) { Log.i(TAG, "probe unbind failed " + error.getClass().getSimpleName()); }
            probing.set(false);
            Log.i(TAG, "probe cleanup complete");
        }
    }
}
