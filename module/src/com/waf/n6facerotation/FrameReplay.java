package com.waf.n6facerotation;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/** Synthetic inputs for the shipped AON transform; never opens a camera or service. */
public final class FrameReplay {
    private static final String HUB = "com.aon.service.aonsensorhublib.";

    private FrameReplay() { }

    /** Call on a dedicated worker from the DUMP-protected receiver in the AON UI process. */
    public static synchronized String run(ClassLoader loader) {
        if (!"com.aiunit.aon.ui".equals(Application.getProcessName())) {
            return "FRAME_REPLAY REFUSED: requires com.aiunit.aon.ui";
        }
        StringBuilder report = new StringBuilder("FRAME_REPLAY synthetic; real transform/I; sensor-info=true expectations only; no camera\n");
        Map<String, Object> values = null;
        Object previousQueue = null;
        boolean hadQueue = false;
        boolean replacedQueue = false;
        try {
            Class<?> engineHolder = Class.forName("com.aiunit.aon.engine.e", true, loader);
            Object engine = field(engineHolder, "a").get(null);
            Object sensorInfo;
            try {
                Object camera = engine.getClass().getMethod("x").invoke(engine);
                sensorInfo = camera.getClass().getMethod("canGetSensorInfoFromSensorHub").invoke(camera);
            } catch (Throwable unavailable) {
                return "FRAME_REPLAY REFUSED: sensor-info capability unavailable; NOT accepted: "
                        + unavailable.getClass().getSimpleName();
            }
            if (!Boolean.TRUE.equals(sensorInfo)) {
                return "FRAME_REPLAY REFUSED: sensor-info capability=" + sensorInfo
                        + "; this replay accepts only the true branch; NOT accepted";
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> engineValues = (Map<String, Object>) field(engine.getClass(), "c").get(engine);
            values = engineValues;
            hadQueue = values.containsKey("detected_humans");
            previousQueue = values.get("detected_humans");
            if (previousQueue instanceof ArrayList && !((ArrayList<?>) previousQueue).isEmpty()) {
                return "FRAME_REPLAY REFUSED: UI engine has pending results";
            }
            ArrayList<Object> queue = new ArrayList<>();
            values.put("detected_humans", queue);
            replacedQueue = true;

            Object operator = Class.forName("com.aiunit.aon.operator.OSensorHubResultProcess", true, loader)
                    .getDeclaredConstructor().newInstance();
            Method transform = operator.getClass().getDeclaredMethod("transform", Object.class);
            Method facesMethod = engine.getClass().getDeclaredMethod("m");
            Class<?> stateClass = Class.forName("s.a", true, loader);
            Method rotation = stateClass.getDeclaredMethod("I", int.class, ArrayList.class);
            Object state = allocate(stateClass);
            field(stateClass, "i").setInt(state, -1);
            field(stateClass, "j").setInt(state, -1);
            field(stateClass, "k").setInt(state, 0);
            field(stateClass, "l").setFloat(state, 0f);
            final ArrayList<Integer> events = new ArrayList<>();
            // K() can only reach this private handler. No message enters a looper queue.
            Handler sink = new Handler(Looper.getMainLooper()) {
                @Override public boolean sendMessageAtTime(Message message, long uptimeMillis) {
                    if (message.what != 2) throw new IllegalStateException("unexpected replay message");
                    events.add(message.arg1);
                    message.recycle();
                    return true;
                }
            };
            field(stateClass, "m").set(state, sink);

            int frames = 0;
            frames += replay("baseline", loader, operator, transform, engine, facesMethod,
                    state, rotation, queue, events, -93.38f, 0, 90, 1, 176, report);
            for (int imageRotation : new int[]{0, 90, 180, 270}) {
                frames += replay("imageRotation=" + imageRotation, loader, operator, transform,
                        engine, facesMethod, state, rotation, queue, events,
                        -93.38f, imageRotation, 90, 1, 176, report);
            }
            frames += replay("missingAngle", loader, operator, transform, engine, facesMethod,
                    state, rotation, queue, events, null, 90, 0, 1, null, report);
            frames += replay("noFace", loader, operator, transform, engine, facesMethod,
                    state, rotation, queue, events, null, 90, 0, 0, -1, report);
            for (int[] test : new int[][]{{0, 0}, {90, 90}}) {
                frames += replay("validRoll=" + test[0], loader, operator, transform, engine,
                        facesMethod, state, rotation, queue, events, (float) test[0], 90, 0, 1, test[1], report);
            }
            frames += replay("twoFacesSecondLarger", loader, operator, transform, engine,
                    facesMethod, state, rotation, queue, events, -93.38f, 90, 0, 2, 266, report);
            for (float roll : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                frames += replay("nonFinite=" + roll, loader, operator, transform, engine,
                        facesMethod, state, rotation, queue, events, roll, 90, 0, 1, null, report);
            }
            // Literal callback expectations; no implementation of the candidate normalization here.
            float[][] sameFrameCases = {
                    {0f, 0, 0}, {90f, 90, 0}, {180f, 180, 0}, {-90f, 270, 0},
                    {-93.38f, 90, 176}, {-93.38f, 270, 356},
                    {3f, 0, 3}, {93f, 90, 3}, {183f, 180, 3}, {273f, 270, 3}
            };
            for (float[] test : sameFrameCases) {
                frames += replay("sameFrame raw=" + test[0] + " P=" + (int) test[1], loader,
                        operator, transform, engine, facesMethod, state, rotation, queue, events,
                        test[0], 0, (int) test[1], 1, (int) test[2], report);
            }
            // Each phone posture must retain all four relative directions, including 180.
            // These are literal input/output fixtures, not a second normalization algorithm.
            float[][] fourDirectionCases = {
                    {0f, 0, 0}, {90f, 0, 90}, {180f, 0, 180}, {-90f, 0, 270},
                    {90f, 90, 0}, {180f, 90, 90}, {-90f, 90, 180}, {0f, 90, 270},
                    {180f, 180, 0}, {-90f, 180, 90}, {0f, 180, 180}, {90f, 180, 270},
                    {-90f, 270, 0}, {0f, 270, 90}, {90f, 270, 180}, {180f, 270, 270}
            };
            for (float[] test : fourDirectionCases) {
                frames += replay("fourDirections raw=" + test[0] + " P=" + (int) test[1], loader,
                        operator, transform, engine, facesMethod, state, rotation, queue, events,
                        test[0], 0, (int) test[1], 1, (int) test[2], report);
            }
            report.append("PASS true-branch original-transform/shared-face preservation/literal I callback assertions: ")
                    .append(frames).append(" frames. Installed hooks exercised; real HAL/side-lying face coordinates NOT accepted.");
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException
                    ? ((InvocationTargetException) error).getTargetException() : error;
            report.append("FAIL ").append(cause.getClass().getSimpleName()).append(": ")
                    .append(cause.getMessage());
        } finally {
            if (replacedQueue) {
                if (hadQueue) values.put("detected_humans", previousQueue);
                else values.remove("detected_humans");
            }
        }
        return report.toString();
    }

    private static int replay(String name, ClassLoader loader, Object operator, Method transform,
            Object engine, Method facesMethod, Object state, Method rotation, ArrayList<Object> queue,
            ArrayList<Integer> events, Float roll, int imageRotation, int phoneRoll, int faceCount,
            Integer expectedEvent, StringBuilder report) throws Exception {
        queue.clear();
        events.clear();
        Object frame = allocate(Class.forName(HUB + "p", true, loader));
        Class<?> fusionClass = Class.forName(HUB + "d", true, loader);
        Object fusion = fusionClass.getDeclaredConstructor().newInstance();
        @SuppressWarnings("unchecked")
        ArrayList<Object> detections = (ArrayList<Object>) field(fusionClass, "a").get(fusion);
        field(fusionClass, "b").setInt(fusion, faceCount);
        field(fusionClass, "c").setFloat(fusion, imageRotation);
        field(fusionClass, "d").setInt(fusion, phoneRoll);
        for (int index = 0; index < faceCount; index++) {
            Object box = allocate(Class.forName(HUB + "c", true, loader));
            Class<?> boxClass = box.getClass();
            field(boxClass, "a").setInt(box, 2);
            field(boxClass, "b").setFloat(box, 0.99f);
            field(boxClass, "c").setFloat(box, 10f);
            field(boxClass, "d").setFloat(box, 10f);
            field(boxClass, "e").setFloat(box, index == 0 ? 50f : 150f);
            field(boxClass, "f").setFloat(box, index == 0 ? 50f : 150f);
            field(boxClass, "g").set(box, new float[14]);
            detections.add(box);
        }
        field(frame.getClass(), "b").set(frame, fusion);
        if (roll != null) {
            Object angle = allocate(Class.forName(HUB + "b", true, loader));
            field(angle.getClass(), "c").setFloat(angle, roll);
            field(frame.getClass(), "c").set(frame, angle);
        }

        transform.invoke(operator, frame);
        @SuppressWarnings("unchecked")
        ArrayList<Object> faces = (ArrayList<Object>) facesMethod.invoke(engine);
        if (faces.size() != faceCount) throw new IllegalStateException(name + ": face count " + faces.size());
        report.append(name).append(" faces=").append(faces.size());
        Object[] originalFaces = faces.toArray();
        float[] originalRolls = new float[faces.size()];
        float[][] originalAngles = new float[faces.size()][];
        float[][] angleContents = new float[faces.size()][];
        for (int index = 0; index < faces.size(); index++) {
            Object face = faces.get(index);
            Class<?> faceClass = face.getClass();
            float outputRoll = field(faceClass, "c").getFloat(face);
            float outputRotation = field(faceClass, "s").getFloat(face);
            float[] angles = (float[]) field(faceClass, "i").get(face);
            originalRolls[index] = outputRoll;
            originalAngles[index] = angles;
            angleContents[index] = angles == null ? null : angles.clone();
            boolean carriesAngle = index == 0 && roll != null;
            float expectedRoll = carriesAngle ? roll : 0f;
            if (Float.compare(outputRoll, expectedRoll) != 0 || outputRotation != imageRotation
                    || (carriesAngle ? angles == null || angles.length != 4
                    || Float.compare(angles[2], roll) != 0 : angles != null)) {
                throw new IllegalStateException(name + ": unexpected original transform output at face " + index);
            }
            report.append(" [").append(index).append(":c=").append(outputRoll)
                    .append(",s=").append(outputRotation).append(",Angle=").append(angles != null).append(']');
        }
        // Use the actual I/H/K methods, with module hooks, and an instance-local output sink.
        rotation.invoke(state, 1, faces);
        if (faces.size() != originalFaces.length) throw new IllegalStateException(name + ": I changed shared face list size");
        for (int index = 0; index < originalFaces.length; index++) {
            Object face = faces.get(index);
            if (face != originalFaces[index]
                    || Float.floatToRawIntBits(field(face.getClass(), "c").getFloat(face))
                    != Float.floatToRawIntBits(originalRolls[index])
                    || field(face.getClass(), "i").get(face) != originalAngles[index]
                    || !Arrays.equals(originalAngles[index], angleContents[index])) {
                throw new IllegalStateException(name + ": I changed original shared face c/i at " + index);
            }
        }
        if (expectedEvent == null ? !events.isEmpty()
                : events.size() != 1 || !expectedEvent.equals(events.get(0))) {
            throw new IllegalStateException(name + ": I callback=" + events + " expected="
                    + (expectedEvent == null ? "suppressed" : expectedEvent));
        }
        report.append(" I=").append(events.isEmpty() ? "suppressed" : events.toString()).append('\n');
        return 1;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Object allocate(Class<?> type) throws Exception {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException noConstructor) {
            // R8 removed constructors from these data carriers; the DEX invokes Object.<init>.
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Object unsafe = field(unsafeClass, "theUnsafe").get(null);
            return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
        }
    }
}
