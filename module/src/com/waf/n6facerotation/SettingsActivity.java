package com.waf.n6facerotation;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SettingsActivity extends Activity {
    private static final String ABOUT = "������ʽ\n�����ҽ�������ȫ�ֻ���ѡӦ���ڼ����ֻ�����仯��ת���ȶ����ʱ���� OPPO ԭ�� AON ��������ʶ��\n\n��ת����\n����������������ֻ��ķ��������Ļ��������ϵͳ�Զ���ת���ء�\n������ʶ��ʧ�ܣ�����ϵͳԭ�й����Զ���ת����������ת���������ص�ǰϵͳ��������\n\nӦ�÷�Χ\n��ȫ�֡��Ե�ǰ�û���Ӧ����Ч������ѡӦ�á�ֻ�ڹ�ѡ��Ӧ������Ч���л�ģʽ�ᱣ����ѡ���ر��ܿ��ؼ�ͣ�ñ�ģ�顣\n\n���÷���\n��Ҫ Root �� LSPosed����װ APK���� LSPosed ���ñ�ģ�飬������ѡ��ϵͳ��ܡ��͡�AONService����Ȼ����������Ҫ���Ƶ�Ӧ���ڱ�Ӧ����ѡ������������� LSPosed ������\n\n���û���\nĿǰ��֤����Ϊ OPPO Find N6��ColorOS 17���������͡�ROM ��ϵͳ��������Ҫ������֤��\n\n��������˽\n����ʶ��ĵȴ�����Լ 0.8 �룬��ǰ�յ���Ч�������ǰ������ԭ��Ӳ���رտ������ӳ١��������������뿪��ѡӦ��ʱֹͣ��ģ�������ȡ����ǰ������δʵ���ع��ġ�\nӦ��ѡ�񱣴��ڱ�������ģ�鲻������ϴ�����ͼ��������־���¼�ǶȺͼ��״̬��Root ��ϼ�¼���ܰ���ǰ̨Ӧ�ð�����";
    private final ArrayList<App> apps = new ArrayList<>();
    private final ArrayList<App> visibleApps = new ArrayList<>();
    private final LruCache<String, Drawable> icons = new LruCache<>(48);
    private Set<String> selected;
    private SharedPreferences prefs;
    private TextView count;
    private TextView empty;
    private EditText search;
    private AppAdapter adapter;
    private boolean loading = true;
    private volatile boolean closed;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ConfigProvider.PREFS, MODE_PRIVATE);
        selected = new HashSet<>(prefs.getStringSet("packages", new HashSet<String>()));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(dp(20), 0, dp(20), 0);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(dp(20) + bars.left, bars.top, dp(20) + bars.right, bars.bottom);
            return insets;
        });
        setContentView(page);

        TextView title = text("�Ǹ���ת", 28, Color.rgb(25, 30, 38));
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(24), 0, dp(8));
        page.addView(title);
        TextView description = text("ת���ֻ�ʱ����ʶ����������\n����������ת��������ѭϵͳ����", 14, Color.rgb(100, 106, 115));
        description.setPadding(0, 0, 0, dp(20));
        page.addView(description);

        Switch enabled = new Switch(this);
        enabled.setText("�����Ǹ���ת");
        enabled.setTextSize(17);
        enabled.setMinHeight(dp(56));
        enabled.setChecked(prefs.getBoolean("enabled", false));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("enabled", checked).apply();
            changed();
        });
        page.addView(enabled, new LinearLayout.LayoutParams(-1, -2));

        RadioGroup scope = new RadioGroup(this);
        scope.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton selectedScope = new RadioButton(this);
        selectedScope.setId(View.generateViewId());
        selectedScope.setText("��ѡӦ��");
        RadioButton allScope = new RadioButton(this);
        allScope.setId(View.generateViewId());
        allScope.setText("ȫ��");
        scope.addView(selectedScope, new RadioGroup.LayoutParams(0, dp(52), 1));
        scope.addView(allScope, new RadioGroup.LayoutParams(0, dp(52), 1));
        boolean scopeAll = prefs.getBoolean("scope_all", false);
        scope.check(scopeAll ? allScope.getId() : selectedScope.getId());
        page.addView(scope, new LinearLayout.LayoutParams(-1, -2));

        TextView help = text("������ʹ��˵��", 14, Color.rgb(0, 119, 104));
        help.setMinHeight(dp(44));
        help.setGravity(Gravity.CENTER_VERTICAL);
        help.setOnClickListener(view -> {
            TextView body = text(ABOUT, 15, Color.rgb(55, 65, 72));
            body.setPadding(dp(24), dp(8), dp(24), dp(16));
            ScrollView content = new ScrollView(this);
            content.addView(body);
            new AlertDialog.Builder(this).setTitle("������ʹ��˵��")
                    .setView(content).setPositiveButton("֪����", null).show();
        });
        page.addView(help, new LinearLayout.LayoutParams(-1, -2));


        LinearLayout appSelection = new LinearLayout(this);
        appSelection.setOrientation(LinearLayout.VERTICAL);
        appSelection.setVisibility(scopeAll ? View.GONE : View.VISIBLE);
        page.addView(appSelection, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(0, dp(22), 0, dp(10));
        TextView choose = text("ѡ��Ӧ��", 18, Color.rgb(25, 30, 38));
        choose.setTypeface(null, Typeface.BOLD);
        heading.addView(choose, new LinearLayout.LayoutParams(0, -2, 1));
        count = text("", 14, Color.rgb(100, 106, 115));
        heading.addView(count);
        appSelection.addView(heading);
        updateCount();

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("����Ӧ��");
        search.setTextSize(16);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        appSelection.addView(search, new LinearLayout.LayoutParams(-1, dp(50)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int n, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int n) { filter(); }
            @Override public void afterTextChanged(Editable value) {}
        });

        empty = text("���ڼ���Ӧ�á�", 15, Color.rgb(100, 106, 115));
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(24), 0, dp(24));
        appSelection.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setEmptyView(empty);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            App app = visibleApps.get(position);
            if (!selected.remove(app.packageName)) selected.add(app.packageName);
            prefs.edit().putStringSet("packages", new HashSet<>(selected)).apply();
            updateCount();
            adapter.notifyDataSetChanged();
            changed();
        });
        appSelection.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        scope.setOnCheckedChangeListener((group, checkedId) -> {
            boolean all = checkedId == allScope.getId();
            prefs.edit().putBoolean("scope_all", all).apply();
            appSelection.setVisibility(all ? View.GONE : View.VISIBLE);
            if (all && search.hasFocus()) {
                search.clearFocus();
                android.view.inputmethod.InputMethodManager keyboard =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
            }
            changed();
        });
        page.setFocusableInTouchMode(true);
        page.requestFocus();
        new Thread(this::loadApps, "rotation-app-list").start();
    }

    private void loadApps() {
        PackageManager manager = getPackageManager();
        ArrayList<App> loaded = new ArrayList<>();
        try {
            List<ApplicationInfo> entries = manager.getInstalledApplications(0);
            for (ApplicationInfo entry : entries) {
                if (closed) return;
                String name = entry.packageName;
                if (name.equals(getPackageName())) continue;
                String label = name;
                try {
                    CharSequence applicationLabel = manager.getApplicationLabel(entry);
                    if (applicationLabel != null) label = applicationLabel.toString();
                } catch (RuntimeException ignored) {
                    // Keep the package searchable when its label resources cannot be read.
                }
                loaded.add(new App(name, label, entry));
            }
            Collator collator = Collator.getInstance(Locale.CHINA);
            Collections.sort(loaded, (left, right) -> {
                int order = collator.compare(left.label, right.label);
                return order != 0 ? order : left.packageName.compareTo(right.packageName);
            });
            runOnUiThread(() -> {
                if (closed) return;
                apps.addAll(loaded);
                loading = false;
                filter();
            });
        } catch (RuntimeException error) {
            runOnUiThread(() -> {
                if (closed) return;
                loading = false;
                empty.setText("Ӧ���б�����ʧ�ܣ������´�");
            });
        }
    }

    private void filter() {
        if (adapter == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visibleApps.clear();
        for (App app : apps) {
            if (app.label.toLowerCase(Locale.ROOT).contains(query)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(query)) visibleApps.add(app);
        }
        empty.setText(loading ? "���ڼ���Ӧ�á�" : query.isEmpty() ? "���޿�ѡ���Ӧ��" : "δ�ҵ�ƥ���Ӧ��");
        adapter.notifyDataSetChanged();
    }

    private void changed() {
        getContentResolver().notifyChange(ConfigProvider.CONFIG_URI, null);
    }

    private void updateCount() { count.setText("��ѡ " + selected.size() + " ��"); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    @Override public void onDestroy() {
        closed = true;
        super.onDestroy();
    }

    private static final class App {
        final String packageName;
        final String label;
        final ApplicationInfo info;
        App(String packageName, String label, ApplicationInfo info) {
            this.packageName = packageName;
            this.label = label;
            this.info = info;
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleApps.size(); }
        @Override public App getItem(int position) { return visibleApps.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled == null) {
                row = new LinearLayout(SettingsActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(10), 0, dp(10));
                row.setMinimumHeight(dp(66));
                ImageView icon = new ImageView(SettingsActivity.this);
                row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
                TextView label = text("", 16, Color.rgb(25, 30, 38));
                label.setMaxLines(2);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(0, -2, 1);
                labelLayout.setMargins(dp(14), 0, dp(8), 0);
                row.addView(label, labelLayout);
                CheckBox check = new CheckBox(SettingsActivity.this);
                check.setClickable(false);
                check.setFocusable(false);
                check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(check, new LinearLayout.LayoutParams(-2, -2));
            } else row = (LinearLayout) recycled;
            App app = getItem(position);
            Drawable icon = icons.get(app.packageName);
            if (icon == null) {
                try {
                    icon = getPackageManager().getApplicationIcon(app.info);
                } catch (RuntimeException ignored) {
                    icon = getPackageManager().getDefaultActivityIcon();
                }
                icons.put(app.packageName, icon);
            }
            ((ImageView) row.getChildAt(0)).setImageDrawable(icon);
            ((TextView) row.getChildAt(1)).setText(app.label);
            ((CheckBox) row.getChildAt(2)).setChecked(selected.contains(app.packageName));
            row.setContentDescription(app.label + (selected.contains(app.packageName) ? "����ѡ��" : "��δѡ��"));
            return row;
        }
    }
}
