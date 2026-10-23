package com.qingping.app;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.qingping.app.guard.AppInfoUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppPickerActivity extends android.app.Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> filtered = new ArrayList<>();

    private ListView listView;
    private ProgressBar progress;
    private EditText searchInput;
    private TextView selectedCount;
    private TextView emptyLabel;
    private Set<String> selected = new HashSet<>();
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);
        selected = Prefs.jumpTargets(this);

        listView = findViewById(R.id.appList);
        progress = findViewById(R.id.progress);
        searchInput = findViewById(R.id.searchInput);
        selectedCount = findViewById(R.id.selectedCount);
        emptyLabel = findViewById(R.id.emptyLabel);

        findViewById(R.id.backButton).setOnClickListener(v -> saveAndFinish());
        findViewById(R.id.saveButton).setOnClickListener(v -> saveAndFinish());

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        loadApps();
    }

    private void loadApps() {
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            List<AppEntry> loaded = new ArrayList<>();
            try {
                PackageManager pm = getPackageManager();
                Intent launcher = new Intent(Intent.ACTION_MAIN);
                launcher.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(launcher, 0);
                Map<String, AppEntry> unique = new LinkedHashMap<>();
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo == null) {
                        continue;
                    }
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.equals(getPackageName()) || unique.containsKey(pkg)) {
                        continue;
                    }
                    CharSequence label = ri.loadLabel(pm);
                    unique.put(pkg, new AppEntry(pkg, label == null ? pkg : label.toString()));
                }
                loaded.addAll(unique.values());
            } catch (Exception ignored) {
            }

            Collator collator = Collator.getInstance(Locale.CHINA);
            loaded.sort((a, b) -> collator.compare(a.label, b.label));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                all.clear();
                all.addAll(loaded);
                progress.setVisibility(View.GONE);
                filter(searchInput.getText().toString());
                if (loaded.isEmpty()) {
                    Toast.makeText(this, R.string.error_no_apps, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void filter(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (AppEntry app : all) {
            if (q.isEmpty() || app.label.toLowerCase(Locale.ROOT).contains(q)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(q)) {
                filtered.add(app);
            }
        }
        adapter = new AppAdapter(filtered);
        listView.setAdapter(adapter);
        emptyLabel.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        updateCount();
    }

    private void toggle(AppEntry app) {
        if (selected.contains(app.packageName)) {
            selected.remove(app.packageName);
        } else {
            selected.add(app.packageName);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateCount();
    }

    private void updateCount() {
        selectedCount.setText(getString(R.string.picker_selected, selected.size()));
    }

    private void saveAndFinish() {
        Prefs.setJumpTargets(this, selected);
        finish();
    }

    @Override
    public void onBackPressed() {
        saveAndFinish();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class AppEntry {
        final String packageName;
        final String label;

        AppEntry(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private final class AppAdapter extends BaseAdapter {
        private final List<AppEntry> data;

        AppAdapter(List<AppEntry> data) {
            this.data = data;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return data.get(position).packageName.hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            Holder holder;
            if (row == null) {
                row = LayoutInflater.from(AppPickerActivity.this)
                        .inflate(R.layout.item_app_picker, parent, false);
                holder = new Holder();
                holder.icon = row.findViewById(R.id.appIcon);
                holder.name = row.findViewById(R.id.appName);
                holder.pkg = row.findViewById(R.id.appPkg);
                holder.check = row.findViewById(R.id.appCheck);
                row.setTag(holder);
            } else {
                holder = (Holder) row.getTag();
            }
            AppEntry app = data.get(position);
            holder.name.setText(app.label);
            holder.pkg.setText(app.packageName);
            Drawable icon = AppInfoUtils.icon(AppPickerActivity.this, app.packageName);
            if (icon != null) {
                holder.icon.setImageDrawable(icon);
            } else {
                holder.icon.setImageResource(R.drawable.ic_apps);
            }
            holder.check.setChecked(selected.contains(app.packageName));
            View.OnClickListener click = v -> toggle(app);
            row.setOnClickListener(click);
            holder.check.setOnClickListener(click);
            return row;
        }
    }

    private static final class Holder {
        ImageView icon;
        TextView name;
        TextView pkg;
        CheckBox check;
    }
}
