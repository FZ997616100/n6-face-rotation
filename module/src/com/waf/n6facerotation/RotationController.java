package com.waf.n6facerotation;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Log;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Owns one on-change orientation sensor and one bounded AON request at a time. */
public final class RotationController implements SensorEventListener {
    private static final String TAG = "N6FaceRotation";
    private static final int COMMAND = 393218;
    private static volatile RotationController instance;
    private final Object displayRotation;
    private final Object display;
    private final Object service;
    private final Object windowLock;
    private final Context context;
    private final Handler worker;
    private SensorManager sensors;
    private Sensor orientation;
    private PowerManager power;
    private UserManager users;
    private volatile Config config = new Config(false, false, new HashSet<String>());
    private volatile int target = -1;
    private volatile boolean available;
    private int appOrientation;
    private long orientationGeneration;
    private boolean monitoring;
    private int lastHardware = -1;
    private int pendingHardware = -1;
    private boolean bound;
    private IBinder aon;
    private Request request;
    private String lastGate;
    private String lastPackage;
    private final Runnable settle = () -> beginRequest(pendingHardware, null);
    private final Runnable expire = () -> finish(request, null, "timeout");

    private static final class Config {
        final boolean enabled, all;
        final Set<String> packages;
        Config(boolean enabled, boolean all, Set<String> packages) {
            this.enabled = enabled; this.all = all; this.packages = packages;
        }
    }

    public static void install(ClassLoader loader) throws Throwable {
        Class<?> rotation = XposedHelpers.findClass("com.android.server.wm.DisplayRotation", loader);
        // The boot image may inline this policy into updateRotationUnchecked.
        java.lang.reflect.Method deopt = XposedBridge.class.getMethod("deoptimizeMethod", java.lang.reflect.Member.class);
        deopt.invoke(null, rotation.getDeclaredMethod("updateRotationUnchecked", boolean.class));
        Class<?> userController = XposedHelpers.findClass("com.android.server.am.UserController", loader);
        deopt.invoke(null, userController.getDeclaredMethod("handleMessage", android.os.Message.class));
        Class<?> activity = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", loader);
        Class<?> task = XposedHelpers.findClass("com.android.server.wm.Task", loader);
        Class<?> tasks = XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", loader);
        deopt.invoke(null, tasks.getDeclaredMethod("setLastResumedActivityUncheckLocked", activity, String.class));
        deopt.invoke(null, tasks.getDeclaredMethod("setFocusedTask", int.class, activity));
        deopt.invoke(null, activity.getDeclaredMethod("moveFocusableActivityToTop", String.class));
        deopt.invoke(null, XposedHelpers.findClass("com.android.server.wm.TaskFragment", loader)
                .getDeclaredMethod("resumeTopActivity", activity, android.app.ActivityOptions.class, boolean.class));
        deopt.invoke(null, XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerServiceExtImpl", loader)
                .getDeclaredMethod("taskFocusChanged", task, task, activity, String.class));
        Class<?> process = XposedHelpers.findClass("com.android.server.am.ProcessRecord", loader);
        Class<?> provider = XposedHelpers.findClass("com.android.server.am.ContentProviderRecord", loader);
        deopt.invoke(null, XposedHelpers.findClass("com.android.server.am.ContentProviderHelperExtImpl", loader)
                .getDeclaredMethod("interceptStartProvider", XposedHelpers.findClass("com.android.server.am.ActivityManagerService", loader),
                        android.content.pm.ProviderInfo.class, int.class, String.class, process, provider));
        XposedHelpers.findAndHookMethod("com.android.server.am.OplusAppStartupManager", loader, "shouldPreventStartProvider",
                process, provider, android.content.pm.ApplicationInfo.class, String.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if ((Integer) p.args[4] != 1000 || p.args[1] == null) return;
                ComponentName name = (ComponentName) XposedHelpers.getObjectField(p.args[1], "name");
                if (new ComponentName("com.waf.n6facerotation", "com.waf.n6facerotation.ConfigProvider").equals(name)) p.setResult(false);
            }
        });
        XposedHelpers.findAndHookMethod(userController, "finishUserUnlocked",
                XposedHelpers.findClass("com.android.server.am.UserState", loader), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                RotationController c = instance;
                if (c != null) c.worker.post(c::readConfig);
            }
        });
        XposedBridge.hookAllConstructors(rotation, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (instance != null || p.hasThrowable()) return;
                try {
                    Object dc = XposedHelpers.getObjectField(p.thisObject, "mDisplayContent");
                    if ((Integer) XposedHelpers.callMethod(dc, "getDisplayId") == 0) {
                        instance = new RotationController(p.thisObject);
                    }
                } catch (Throwable error) { logError("controller construction", error); }
            }
        });
        XposedHelpers.findAndHookMethod(rotation, "rotationForOrientation", int.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    RotationController c = instance;
                    if (c == null || c.displayRotation != p.thisObject) return;
                    int requested = (Integer) p.args[0];
                    if (c.appOrientation != requested) {
                        c.appOrientation = requested;
                        c.orientationGeneration++;
                        c.target = -1;
                        // Let the app's new orientation establish a native baseline, including fullscreen exit.
                        return;
                    }
                    if (c.allowed() && allowsFaceRotation(requested)) {
                        if (c.target >= 0) p.setResult(c.target);
                        else if (XposedHelpers.getIntField(p.thisObject, "mUserRotationMode") == 1) {
                            // Losing a face must not restore the lock from before the face-driven turn.
                            // Read the live mode so enabling auto-rotate releases this hold immediately.
                            p.setResult(XposedHelpers.getIntField(p.thisObject, "mRotation"));
                        }
                    }
                } catch (Throwable error) { logError("rotation policy", error); }
            }
        });
        XposedHelpers.findAndHookMethod("com.android.server.wm.WindowManagerService", loader, "systemReady", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                RotationController c = instance;
                if (c != null) c.worker.post(c::start);
            }
        });
        XposedHelpers.findAndHookMethod("com.android.server.wm.DisplayContent", loader, "setFocusedApp",
                XposedHelpers.findClass("com.android.server.wm.ActivityRecord", loader), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                RotationController c = instance;
                if (c != null && c.display == p.thisObject && Boolean.TRUE.equals(p.getResult())) c.worker.post(() -> {
                    if (c.users != null) c.readConfig();
                });
            }
        });
        Log.i(TAG, "0.4.9 independent rotation hooks installed");
        XposedBridge.log("N6FaceRotation 0.4.9 independent rotation hooks installed");
    }

    private RotationController(Object rotation) {
        displayRotation = rotation;
        appOrientation = XposedHelpers.getIntField(rotation, "mLastOrientation");
        display = XposedHelpers.getObjectField(rotation, "mDisplayContent");
        service = XposedHelpers.getObjectField(rotation, "mService");
        windowLock = XposedHelpers.getObjectField(rotation, "mLock");
        context = (Context) XposedHelpers.getObjectField(rotation, "mContext");
        HandlerThread thread = new HandlerThread("N6FaceRotation");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    private void start() {
        sensors = context.getSystemService(SensorManager.class);
        orientation = sensors.getDefaultSensor(27);
        power = context.getSystemService(PowerManager.class);
        users = context.getSystemService(UserManager.class);
        context.getContentResolver().registerContentObserver(ConfigProvider.CONFIG_URI, false,
                new ContentObserver(worker) { @Override public void onChange(boolean self) { readConfig(); } });
        IntentFilter state = new IntentFilter(Intent.ACTION_SCREEN_ON);
        state.addAction(Intent.ACTION_SCREEN_OFF);
        state.addAction(Intent.ACTION_USER_PRESENT);
        state.addAction(Intent.ACTION_USER_UNLOCKED);
        state.addAction(Intent.ACTION_BOOT_COMPLETED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                Log.i(TAG, "state event=" + i.getAction() + " unlocked=" + users.isUserUnlocked());
                readConfig();
            }
        }, state, null, worker, Context.RECEIVER_EXPORTED);
        // Explicit shell-only diagnostics use the production sensor/request/application path.
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String snapshot;
                synchronized (windowLock) {
                    snapshot = "0.4.9 enabled=" + config.enabled + " global=" + config.all + " available=" + available
                            + " monitoring=" + monitoring + " userUnlocked=" + users.isUserUnlocked()
                            + " interactive=" + power.isInteractive() + " keyguard=" + XposedHelpers.callMethod(service, "isKeyguardLocked")
                            + " package=" + focusedPackage() + " target=" + target + " hardware=" + lastHardware;
                }
                setResultData(snapshot);
                Log.i(TAG, "STATUS " + snapshot);
                int hardware = i.getIntExtra("hardware", -1);
                int angle = i.getIntExtra("angle", -2);
                if (hardware >= 0 && hardware < 4 && angle >= -1 && angle < 360) {
                    boolean real = i.getBooleanExtra("real", false);
                    Log.i(TAG, "TEST " + (real ? "real-AON" : "synthetic") + " hardware=" + hardware + " angle=" + angle);
                    beginRequest(hardware, real ? null : angle);
                }
            }
        }, new IntentFilter("com.waf.n6facerotation.TEST"), "android.permission.DUMP", worker, Context.RECEIVER_EXPORTED);
        readConfig();
        Log.i(TAG, "controller ready orientationSensor=" + (orientation == null ? "missing" : orientation.getName()));
    }

    private void readConfig() {
        if (!users.isUserUnlocked()) { refresh(); return; }
        try (Cursor cursor = context.getContentResolver().query(ConfigProvider.CONFIG_URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                config = new Config(cursor.getInt(0) == 1, cursor.getInt(2) == 1,
                        new HashSet<>(Arrays.asList(cursor.getString(1).split("\n"))));
                Log.i(TAG, "config read enabled=" + config.enabled + " global=" + config.all);
            }
        } catch (Throwable error) { logError("configuration read", error); }
        refresh();
    }

    // The caller already holds the window lock when this is used by the policy hook.
    private boolean allowed() {
        Config value = config;
        if (!value.enabled || !available) return false;
        String packageName = focusedPackage();
        if (packageName == null) return false;
        return value.all || value.packages.contains(packageName);
    }

    private String focusedPackage() {
        Object app = XposedHelpers.getObjectField(display, "mFocusedApp");
        return app == null ? null : (String) XposedHelpers.getObjectField(app, "packageName");
    }

    private boolean allowedFromWorker() {
        synchronized (windowLock) { return allowed(); }
    }

    private static boolean allowsFaceRotation(int requested) {
        switch (requested) {
            case ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED:
            case ActivityInfo.SCREEN_ORIENTATION_USER:
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR:
            case ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR:
            case ActivityInfo.SCREEN_ORIENTATION_FULL_USER:
                return true;
            default:
                return false;
        }
    }

    private void refresh() {
        if (power == null) return;
        try {
            available = power.isInteractive() && !(Boolean) XposedHelpers.callMethod(service, "isKeyguardLocked") && users.isUserUnlocked();
            String packageName;
            synchronized (windowLock) { packageName = focusedPackage(); }
            if (!java.util.Objects.equals(packageName, lastPackage)) {
                lastPackage = packageName;
                worker.removeCallbacks(settle);
                cancelRequest();
            }
            boolean allow = allowedFromWorker();
            String gate = "enabled=" + config.enabled + " global=" + config.all + " allowed=" + allow;
            if (!gate.equals(lastGate)) { lastGate = gate; Log.i(TAG, "controller gate " + gate); }
            if (allow && !monitoring && orientation != null) {
                synchronized (windowLock) { target = XposedHelpers.getIntField(displayRotation, "mRotation"); }
                lastHardware = -1;
                monitoring = sensors.registerListener(this, orientation, SensorManager.SENSOR_DELAY_NORMAL, worker);
                Log.i(TAG, "orientation monitor=" + monitoring + " hold=" + target);
            } else if (!allow) {
                boolean wasActive = monitoring || target >= 0;
                if (monitoring) sensors.unregisterListener(this);
                monitoring = false;
                lastHardware = -1;
                target = -1;
                worker.removeCallbacks(settle);
                cancelRequest();
                unbind();
                if (wasActive) applyRotation();
            }
        } catch (Throwable error) { logError("scope refresh", error); }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        int hardware = (int) event.values[0];
        if (hardware == 4 && lastHardware == -1) { lastHardware = 4; return; }
        if (hardware < 0 || hardware > 3 || hardware == lastHardware) return;
        int previous = lastHardware;
        lastHardware = hardware;
        if (previous == -1) { Log.i(TAG, "orientation baseline=" + hardware); return; }
        if (!allowedFromWorker()) { refresh(); return; }
        cancelRequest();
        synchronized (windowLock) { target = XposedHelpers.getIntField(displayRotation, "mRotation"); }
        pendingHardware = hardware;
        worker.removeCallbacks(settle);
        worker.postDelayed(settle, 150L);
        Log.i(TAG, "phone moved " + previous + "->" + hardware);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void beginRequest(int hardware, Integer syntheticAngle) {
        try {
            if (hardware < 0 || hardware > 3 || !allowedFromWorker()) { refresh(); return; }
            cancelRequest();
            synchronized (windowLock) {
                if (!allowsFaceRotation(appOrientation)) { target = -1; return; }
                target = XposedHelpers.getIntField(displayRotation, "mRotation");
                request = new Request(hardware, focusedPackage(), orientationGeneration);
            }
            if (syntheticAngle != null) {
                Request test = request;
                worker.postDelayed(() -> finish(test, syntheticAngle >= 0 ? syntheticAngle : null, "synthetic"), 80L);
            } else if (aon != null) startRequest();
            else {
                if (!bound) bound = context.bindService(new Intent().setComponent(new ComponentName("com.aiunit.aon", "com.aiunit.aon.AONService")), connection, Context.BIND_AUTO_CREATE);
                if (!bound) finish(request, null, "bind-failed");
                else worker.postDelayed(expire, 1800L);
            }
        } catch (Throwable error) { logError("begin recognition", error); finish(request, null, "error"); }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            worker.post(() -> { aon = binder; if (request != null) startRequest(); });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            worker.post(() -> { aon = null; finish(request, null, "disconnected"); });
        }
        @Override public void onNullBinding(ComponentName name) { worker.post(() -> { finish(request, null, "null-binding"); unbind(); }); }
        @Override public void onBindingDied(ComponentName name) { worker.post(() -> { finish(request, null, "binding-died"); unbind(); }); }
    };

    private void startRequest() {
        Request current = request;
        if (current == null || current.registered) return;
        worker.removeCallbacks(expire);
        if (!allowedFromWorker()) { refresh(); return; }
        try {
            int registration = serviceCall(aon, 5, current.listener);
            current.registered = registration == 0;
            if (!current.registered) { finish(current, null, "register=" + registration); return; }
            current.startAttempted = true;
            int start = serviceCall(aon, 3, null);
            Log.i(TAG, "recognition start=" + start + " hardware=" + current.hardware + " window=800ms");
            if (start != 0) { finish(current, null, "start=" + start); return; }
            worker.postDelayed(expire, 800L);
        } catch (Throwable error) { logError("AON start", error); finish(current, null, "start-error"); }
    }

    private final class Request {
        final int hardware;
        final String packageName;
        final long orientationGeneration;
        boolean registered, startAttempted;
        final Binder listener = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString("com.aiunit.aon.utils.IAONEventListener"); return true; }
                if (code == 1 || code == 2) {
                    data.enforceInterface("com.aiunit.aon.utils.IAONEventListener");
                    if (code == 1) {
                        int type = data.readInt(), angle = data.readInt();
                        if (type == COMMAND && angle >= 0 && angle < 360) worker.post(() -> finish(Request.this, angle, "face"));
                    }
                    if (reply != null) { reply.writeNoException(); if (code == 2) reply.writeInt(0); }
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        Request(int hardware, String packageName, long orientationGeneration) {
            this.hardware = hardware; this.packageName = packageName; this.orientationGeneration = orientationGeneration;
        }
    }

    private void finish(Request current, Integer angle, String reason) {
        if (current == null || current != request) return;
        // Without a face, the policy hook holds the current screen only while auto-rotate is off.
        int result = angle == null ? -1 : ((angle + 45) / 90) % 4;
        cancelRequest();
        try {
            if (!allowedFromWorker()) { refresh(); return; }
            synchronized (windowLock) {
                if (!java.util.Objects.equals(current.packageName, focusedPackage())) { worker.post(this::refresh); return; }
                if (current.orientationGeneration != orientationGeneration) return;
                target = result;
            }
            Log.i(TAG, "rotation decision reason=" + reason + " hardware=" + current.hardware + " face=" + angle + " target=" + target);
            applyRotation();
        } catch (Throwable error) { logError("rotation result", error); }
    }

    private void cancelRequest() {
        worker.removeCallbacks(expire);
        Request old = request;
        request = null;
        if (old == null || aon == null) return;
        if (old.startAttempted) try { Log.i(TAG, "recognition stop=" + serviceCall(aon, 4, null)); }
            catch (Throwable error) { logError("AON stop", error); }
        if (old.registered) try { Log.i(TAG, "recognition unregister=" + serviceCall(aon, 6, old.listener)); }
            catch (Throwable error) { logError("AON unregister", error); }
    }

    private void unbind() {
        if (bound) {
            try { context.unbindService(connection); } catch (Throwable error) { logError("AON unbind", error); }
        }
        bound = false;
        aon = null;
    }

    private void applyRotation() {
        XposedHelpers.callMethod(service, "updateRotation", false, false);
        synchronized (windowLock) { Log.i(TAG, "display applied=" + XposedHelpers.getIntField(displayRotation, "mRotation") + " target=" + target); }
    }

    private static int serviceCall(IBinder binder, int code, IBinder listener) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.aiunit.aon.utils.IAONService");
            if (code == 5 || code == 6) data.writeStrongBinder(listener);
            data.writeInt(COMMAND);
            if (!binder.transact(code, data, reply, 0)) throw new RemoteException("unsupported AON transaction");
            reply.readException();
            return reply.readInt();
        } finally { reply.recycle(); data.recycle(); }
    }

    private static void logError(String action, Throwable error) {
        Log.i(TAG, action + " failed " + error.getClass().getSimpleName(), error);
    }
}
