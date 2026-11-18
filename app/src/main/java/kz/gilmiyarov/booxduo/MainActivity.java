package kz.gilmiyarov.booxduo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Small, offline, stock-Android HOME replacement designed for e-paper.
 * No root, accessibility, storage, network, or all-packages permissions.
 *
 * A launcher can reliably start an app, but it cannot read the other app's
 * private book-progress data. ReadEra/AlReaderX control their resume behavior.
 */
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(23, 23, 23);
    private static final int MUTED = Color.rgb(96, 96, 96);
    private static final int EDGE = Color.rgb(197, 197, 197);
    private static final int PAPER = Color.WHITE;
    private static final int WASH = Color.rgb(246, 246, 245);
    private static final int PICK_COVER_BASE = 410;

    private final List<AppEntry> apps = new ArrayList<>();
    private SharedPreferences prefs;

    private static final class AppEntry {
        final String label;
        final ComponentName component;
        AppEntry(String label, ComponentName component) {
            this.label = label;
            this.component = component;
        }
        String description() { return label + "  ·  " + component.getPackageName(); }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("duo", Context.MODE_PRIVATE);
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        scanApps();
        autoPickReaders();
        renderHome();
    }

    @Override protected void onResume() {
        super.onResume();
        // Refresh date and reader choices after returning from a launched app.
        if (prefs != null) {
            scanApps();
            autoPickReaders();
            renderHome();
        }
    }

    private void scanApps() {
        apps.clear();
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(query, 0);
        for (ResolveInfo info : found) {
            if (info.activityInfo == null || getPackageName().equals(info.activityInfo.packageName)) continue;
            CharSequence label = info.loadLabel(pm);
            String name = label != null ? label.toString() : info.activityInfo.packageName;
            ComponentName component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            boolean exists = false;
            for (AppEntry old : apps) {
                if (old.component.equals(component)) { exists = true; break; }
            }
            if (!exists) apps.add(new AppEntry(name, component));
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
    }

    private void autoPickReaders() {
        for (int slot = 0; slot < 2; slot++) {
            if (prefs.contains("target_" + slot)) continue;
            AppEntry best = null;
            int bestScore = 0;
            for (AppEntry entry : apps) {
                String s = (entry.label + " " + entry.component.getPackageName()).toLowerCase(Locale.ROOT);
                int score = 0;
                if (slot == 0 && s.contains("readera")) score = 10;
                if (slot == 1 && s.contains("alreaderx")) score = 12;
                else if (slot == 1 && s.contains("alreader")) score = 9;
                if (score > bestScore) { bestScore = score; best = entry; }
            }
            if (best != null) saveTarget(slot, best);
        }
    }

    private void saveTarget(int slot, AppEntry app) {
        prefs.edit().putString("target_" + slot, app.component.flattenToString()).apply();
    }

    private AppEntry selectedApp(int slot) {
        String saved = prefs.getString("target_" + slot, null);
        if (saved == null) return null;
        ComponentName wanted = ComponentName.unflattenFromString(saved);
        if (wanted == null) return null;
        for (AppEntry entry : apps) {
            if (entry.component.equals(wanted)) return entry;
        }
        return null;
    }

    private void launchApp(AppEntry entry) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(entry.component);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Не удалось открыть приложение. Выбери его заново в настройках.", Toast.LENGTH_LONG).show();
        }
    }

    private void openReader(int slot) {
        AppEntry selected = selectedApp(slot);
        if (selected == null) {
            Toast.makeText(this, "Сначала выбери читалку", Toast.LENGTH_SHORT).show();
            showAppPicker(slot, null);
        } else {
            launchApp(selected);
        }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable panel(int color, int lineColor, float cornerDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(cornerDp));
        drawable.setStroke(dp(1), lineColor);
        return drawable;
    }
    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }
    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        params.setMargins(dp(l), dp(t), dp(r), dp(b));
        return params;
    }
    private View separator() {
        View line = new View(this);
        line.setBackgroundColor(EDGE);
        return line;
    }

    private void renderHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout body = vertical();
        body.setPadding(dp(19), dp(16), dp(19), dp(22));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        TextView eyebrow = text("ДВА ЧИТАТЕЛЯ  /  ОДНО УСТРОЙСТВО", 10, MUTED, true);
        eyebrow.setLetterSpacing(.08f);
        body.addView(eyebrow, margins(-1, -2, 0, 0, 0, 7));
        body.addView(text("Наши книги", 28, INK, true), margins(-1, -2, 0, 0, 0, 4));
        String date = new SimpleDateFormat("EEEE, d MMMM", new Locale("ru", "RU")).format(new Date());
        body.addView(text(date, 13, MUTED, false), margins(-1, -2, 0, 0, 0, 19));
        body.addView(separator(), margins(-1, 1, 0, 0, 0, 17));

        body.addView(readerCard(0, "РУСЛАН", "Моя книга", "Р"), margins(-1, 169, 0, 0, 0, 13));
        body.addView(readerCard(1, "АННА", "Книга Анны", "А"), margins(-1, 169, 0, 0, 0, 20));

        TextView quickTitle = text("БЫСТРЫЙ ДОСТУП", 11, MUTED, true);
        quickTitle.setLetterSpacing(.07f);
        body.addView(quickTitle, margins(-1, -2, 0, 0, 0, 10));

        LinearLayout shortcuts = horizontal();
        body.addView(shortcuts, margins(-1, 45, 0, 0, 0, 0));
        addShortcut(shortcuts, "Все приложения", () -> showAppPicker(-1, null), true);
        addShortcut(shortcuts, "Настройки книг", this::showSettings, false);

        LinearLayout spacer = vertical();
        body.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView footer = text("BOOX DUO   ·   E-INK EDITION   ·   0.1", 10, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        body.addView(footer, margins(-1, -2, 0, 23, 0, 0));
        setContentView(scroll);
    }

    private void addShortcut(LinearLayout parent, String title, Runnable action, boolean first) {
        TextView button = text(title, 12, INK, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(panel(WASH, EDGE, 8));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        if (first) params.rightMargin = dp(10);
        parent.addView(button, params);
    }

    private View readerCard(int slot, String person, String defaultTitle, String monogram) {
        LinearLayout card = horizontal();
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(panel(PAPER, EDGE, 10));
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setOnClickListener(v -> openReader(slot));
        card.setOnLongClickListener(v -> { showSettings(); return true; });

        FrameLayout cover = new FrameLayout(this);
        cover.setBackground(panel(WASH, EDGE, 4));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(95), -1);
        cp.rightMargin = dp(13);
        card.addView(cover, cp);

        Uri coverUri = getCover(slot);
        if (coverUri != null) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap bitmap = decodeCover(coverUri);
            if (bitmap != null) {
                image.setImageBitmap(bitmap);
                cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
            } else {
                addMonogram(cover, monogram);
            }
        } else {
            addMonogram(cover, monogram);
        }

        LinearLayout info = vertical();
        info.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(info, new LinearLayout.LayoutParams(0, -1, 1));
        TextView label = text(person, 12, MUTED, true);
        label.setLetterSpacing(.10f);
        info.addView(label, margins(-1, -2, 0, 1, 0, 8));

        String bookTitle = prefs.getString("title_" + slot, defaultTitle);
        if (TextUtils.isEmpty(bookTitle)) bookTitle = defaultTitle;
        TextView title = text(bookTitle, 19, INK, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title, margins(-1, -2, 0, 0, 0, 4));
        AppEntry app = selectedApp(slot);
        TextView appLabel = text(app != null ? app.label : "Выбрать приложение", 12, MUTED, false);
        appLabel.setMaxLines(1);
        appLabel.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(appLabel, margins(-1, -2, 0, 0, 0, 4));

        View spacer = new View(this);
        info.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
        info.addView(separator(), margins(-1, 1, 0, 3, 0, 9));
        TextView launch = text(app != null ? "ОТКРЫТЬ ЧИТАЛКУ   →" : "НАСТРОИТЬ   →", 11, INK, true);
        launch.setLetterSpacing(.02f);
        info.addView(launch, margins(-1, -2, 0, 0, 0, 1));
        return card;
    }

    private void addMonogram(FrameLayout parent, String monogram) {
        TextView letter = text(monogram, 37, INK, false);
        letter.setGravity(Gravity.CENTER);
        letter.setTypeface(Typeface.SERIF, Typeface.NORMAL);
        parent.addView(letter, new FrameLayout.LayoutParams(-1, -1));
    }

    private Uri getCover(int slot) {
        String value = prefs.getString("cover_" + slot, null);
        if (value == null) return null;
        try { return Uri.parse(value); } catch (Exception e) { return null; }
    }
    private Bitmap decodeCover(Uri uri) {
        try (InputStream sample = getContentResolver().openInputStream(uri)) {
            if (sample == null) return null;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(sample, null, opt);
            int sampleSize = 1;
            while (opt.outWidth / sampleSize > 360 || opt.outHeight / sampleSize > 480) sampleSize *= 2;
            BitmapFactory.Options result = new BitmapFactory.Options();
            result.inSampleSize = sampleSize;
            try (InputStream full = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(full, null, result);
            }
        } catch (Exception ignored) { return null; }
    }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(8), dp(18), dp(8));
        scroll.addView(content);
        content.addView(text("Редактируй названия и обложки. Приложения не теряют свою историю чтения.", 13, MUTED, false),
                margins(-1, -2, 0, 0, 0, 12));
        for (int slot = 0; slot < 2; slot++) addSettingsForSlot(content, slot);
        content.addView(simpleAction("Выбрать домашний экран Android", this::openHomeSettings),
                margins(-1, 47, 0, 14, 0, 4));
        content.addView(text("Если BOOX не разрешит заменить главный экран, запускай «Наши книги» из списка приложений. Это безопасно и без root.",
                12, MUTED, false), margins(-1, -2, 0, 8, 0, 5));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Настройки наших книг")
                .setView(scroll)
                .setPositiveButton("Готово", (d, w) -> renderHome())
                .create();
        dialog.setOnDismissListener(d -> renderHome());
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void addSettingsForSlot(LinearLayout parent, int slot) {
        String name = slot == 0 ? "Руслан / ReadEra" : "Анна / AlReaderX Pro";
        parent.addView(text(name, 17, INK, true), margins(-1, -2, 0, 13, 0, 5));
        AppEntry current = selectedApp(slot);
        TextView currentApp = text("Приложение: " + (current == null ? "не выбрано" : current.label), 12, MUTED, false);
        parent.addView(currentApp, margins(-1, -2, 0, 0, 0, 5));
        EditText editTitle = new EditText(this);
        editTitle.setTextSize(14);
        editTitle.setTextColor(INK);
        editTitle.setSingleLine(true);
        editTitle.setHint("Название книги (необязательно)");
        editTitle.setText(prefs.getString("title_" + slot, slot == 0 ? "Моя книга" : "Книга Анны"));
        editTitle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                prefs.edit().putString("title_" + slot, s.toString()).apply();
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        parent.addView(editTitle, margins(-1, 44, 0, 0, 0, 6));
        parent.addView(simpleAction("Выбрать приложение", () -> showAppPicker(slot, () -> {
            AppEntry picked = selectedApp(slot);
            currentApp.setText("Приложение: " + (picked == null ? "не выбрано" : picked.label));
        })), margins(-1, 43, 0, 0, 0, 5));
        parent.addView(simpleAction("Поставить обложку из файлов", () -> pickCover(slot)),
                margins(-1, 43, 0, 0, 0, 5));
        parent.addView(simpleAction("Убрать обложку", () -> {
            prefs.edit().remove("cover_" + slot).apply();
            Toast.makeText(this, "Обложка удалена", Toast.LENGTH_SHORT).show();
        }), margins(-1, 43, 0, 0, 0, 4));
        parent.addView(separator(), margins(-1, 1, 0, 13, 0, 0));
    }

    private View simpleAction(String title, Runnable action) {
        TextView button = text(title, 14, INK, false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(panel(WASH, EDGE, 7));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void showAppPicker(int slot, Runnable updated) {
        LinearLayout content = vertical();
        content.setPadding(dp(14), dp(7), dp(14), 0);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setHint("Поиск по названию приложения");
        content.addView(search, margins(-1, 49, 0, 0, 0, 5));
        ListView list = new ListView(this);
        ArrayList<AppEntry> filtered = new ArrayList<>(apps);
        ArrayList<String> labels = new ArrayList<>();
        for (AppEntry e : filtered) labels.add(e.description());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(360)));
        AlertDialog picker = new AlertDialog.Builder(this)
                .setTitle(slot < 0 ? "Открыть приложение" : "Выбери свою читалку")
                .setView(content)
                .setNegativeButton("Отмена", null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filtered.size()) return;
            AppEntry selection = filtered.get(position);
            if (slot < 0) launchApp(selection);
            else {
                saveTarget(slot, selection);
                if (updated != null) updated.run();
                renderHome();
            }
            picker.dismiss();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);
                filtered.clear();
                labels.clear();
                for (AppEntry entry : apps) {
                    if (entry.description().toLowerCase(Locale.ROOT).contains(query)) {
                        filtered.add(entry);
                        labels.add(entry.description());
                    }
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        picker.show();
    }

    private void pickCover(int slot) {
        Intent select = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        select.addCategory(Intent.CATEGORY_OPENABLE);
        select.setType("image/*");
        try {
            startActivityForResult(select, PICK_COVER_BASE + slot);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Не найден файловый менеджер для выбора изображения", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_COVER_BASE && requestCode != PICK_COVER_BASE + 1) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        int slot = requestCode - PICK_COVER_BASE;
        Uri uri = data.getData();
        try {
            int readFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, readFlags);
        } catch (SecurityException ignored) {
            // Some document providers grant session-only access; no crash.
        }
        prefs.edit().putString("cover_" + slot, uri.toString()).apply();
        renderHome();
    }

    private void openHomeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "BOOX не открыл выбор главного экрана. Запускай оболочку как приложение.", Toast.LENGTH_LONG).show();
        }
    }
}
