package com.waf.n6facerotation;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/** The manifest restricts external reads to holders of android.permission.DUMP. */
public final class ConfigProvider extends ContentProvider {
    public static final String PREFS = "rotation_config";
    public static final Uri CONFIG_URI = Uri.parse("content://com.waf.n6facerotation.config/config");
    public static final int DEFAULT_WAIT_MS = 800;

    public static int normalizeWaitMs(int value) {
        return value == 200 || value == 500 || value == 800 ? value : DEFAULT_WAIT_MS;
    }

    public static int readWaitMs(SharedPreferences prefs) {
        try {
            return normalizeWaitMs(prefs.getInt("wait_ms", DEFAULT_WAIT_MS));
        } catch (ClassCastException invalid) {
            return DEFAULT_WAIT_MS;
        }
    }

    @Override public boolean onCreate() {
        return true;
    }

    private void requireConfig(Uri uri) {
        if (!CONFIG_URI.equals(uri)) {
            throw new IllegalArgumentException("Unknown configuration URI: " + uri);
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        requireConfig(uri);
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS, 0);
        ArrayList<String> packages = new ArrayList<>(
                prefs.getStringSet("packages", new HashSet<String>()));
        Collections.sort(packages);
        MatrixCursor cursor = new MatrixCursor(new String[] {"enabled", "packages", "scope_all", "wait_ms"}, 1);
        cursor.addRow(new Object[] {prefs.getBoolean("enabled", false) ? 1 : 0,
                android.text.TextUtils.join("\n", packages),
                prefs.getBoolean("scope_all", false) ? 1 : 0,
                readWaitMs(prefs)});
        cursor.setNotificationUri(getContext().getContentResolver(), CONFIG_URI);
        return cursor;
    }

    @Override public String getType(Uri uri) {
        requireConfig(uri);
        return "vnd.android.cursor.item/vnd.com.waf.n6facerotation.config";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Configuration is read-only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Configuration is read-only");
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("Configuration is read-only");
    }
}
