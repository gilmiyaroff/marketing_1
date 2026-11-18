package kz.gilmiyarov.booxduo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.ClipData;
import android.database.Cursor;
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
import android.provider.DocumentsContract;
import android.os.Environment;
import android.os.StatFs;
import android.text.format.Formatter;
import android.content.ContentResolver;
import android.provider.OpenableColumns;
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
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Small, offline, stock-Android HOME replacement designed for e-paper.
 * No root, network, or all-packages permissions. Optional storage access is user-granted.
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
    private static final int PICK_BOOK_BASE = 420;
    private static final int PICK_FILE_FROM_STORAGE = 430;
    private static final int PICK_BROWSE_FOLDER = 510;
    private int activeTab = 0;
    private final List<String> folderTrail = new ArrayList<>();
    // The catalog is built on a worker thread to avoid freezing the e-paper UI.
    private FilesCatalog.Snapshot storageSnapshot;
    private boolean storageScanBusy = false;
    private int fileCategory = -1;
    private File currentStorageFolder = null;
    private boolean priorFullAccess = false;

    private final List<AppEntry> apps = new ArrayList<>();
    private SharedPreferences prefs;
    private BooxLibrary.Result booxLibrary;
    private boolean booxLoading = false;

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
        autoPickFileManager();
        renderHome();
    }

    @Override protected void onResume() {
        super.onResume();
        // Refresh date and reader choices after returning from a launched app.
        if (prefs != null) {
            scanApps();
            autoPickReaders();
            autoPickFileManager();
            boolean full = FilesCatalog.canRead();
            if (full != priorFullAccess) {
                storageSnapshot = null;
                currentStorageFolder = null;
                fileCategory = -1;
                priorFullAccess = full;
            }
            showTab(activeTab);
            refreshBooxLibrary(false);
        }
    }

    private void refreshBooxLibrary(boolean showMessage) {
        if (booxLoading) return;
        booxLoading = true;
        new Thread(() -> {
            BooxLibrary.Result result = BooxLibrary.readAll(getContentResolver());
            runOnUiThread(() -> {
                booxLibrary = result;
                booxLoading = false;
                if (isFinishing() || isDestroyed()) return;
                if (showMessage) Toast.makeText(this,
                        "Библиотека BOOX: " + result.details, Toast.LENGTH_LONG).show();
                if (activeTab == 0 || activeTab == 3) showTab(activeTab);
            });
        }, "boox-library-read-only").start();
    }

    private BooxLibrary.Book linkedBooxBook(int slot) {
        if (booxLibrary == null || !"ok".equals(booxLibrary.status)) return null;
        BooxLibrary.Book chosen = BooxLibrary.match(booxLibrary,
                prefs.getString("boox_link_" + slot, null));
        if (chosen != null) return chosen;
        // Exact filename match is only safe when one unique book has that name.
        String filename = prefs.getString("book_name_" + slot, "");
        if (filename.isEmpty()) return null;
        BooxLibrary.Book found = null;
        for (BooxLibrary.Book book : booxLibrary.books) {
            String p = book.path.replace('\\', '/');
            if (!filename.equalsIgnoreCase(p.substring(p.lastIndexOf('/')+1))) continue;
            if (found != null) return null; // ambiguous; user must choose manually
            found = book;
        }
        return found;
    }

    private void showBooxBookPicker(int slot) {
        if (booxLibrary == null) {
            Toast.makeText(this, "Проверяю библиотеку BOOX", Toast.LENGTH_SHORT).show();
            refreshBooxLibrary(true);
            return;
        }
        if (!"ok".equals(booxLibrary.status)) {
            new AlertDialog.Builder(this).setTitle("BOOX библиотека")
                .setMessage(booxLibrary.details + ". На этой модели интеграция может быть недоступна.")
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Проверить снова", (d,w) -> refreshBooxLibrary(true)).show();
            return;
        }
        if (booxLibrary.books.isEmpty()) {
            Toast.makeText(this, "BOOX не возвращает записи книг", Toast.LENGTH_LONG).show();
            return;
        }
        int max = Math.min(booxLibrary.books.size(), 130);
        CharSequence[] names = new CharSequence[max];
        for (int i=0;i<max;i++) names[i] = booxLibrary.books.get(i).description();
        new AlertDialog.Builder(this)
            .setTitle("Выбери книгу из библиотеки BOOX")
            .setItems(names, (d, index) -> {
                BooxLibrary.Book book = booxLibrary.books.get(index);
                prefs.edit().putString("boox_link_" + slot, book.key()).apply();
                if (!book.name.isEmpty()) prefs.edit().putString("title_" + slot, book.name).apply();
                showTab(activeTab);
                Toast.makeText(this, "BOOX: книга связана. Читалка не менялась.", Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton("Отвязать", (d,w) -> {
                prefs.edit().remove("boox_link_" + slot).apply();
                showTab(activeTab);
            })
            .setNegativeButton("Отмена", null).show();
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

    private void autoPickFileManager() {
        if (prefs.contains("target_2")) return;
        for (AppEntry entry : apps) {
            String label = entry.label.toLowerCase(Locale.ROOT);
            if (label.equals("файлы") || label.equals("files")
                    || label.contains("файловый менеджер") || label.contains("file manager")
                    || label.contains("внутренняя память") || label.equals("storage")
                    || label.equals("проводник")) {
                saveTarget(2, entry);
                return;
            }
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
            // Do not RESET_TASK_IF_NEEDED: this can return a resumed reader to its library.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Не удалось открыть приложение. Выбери его заново в настройках.", Toast.LENGTH_LONG).show();
        }
    }

    private void openReader(int slot) {
        prefs.edit().putLong("last_launch_" + slot, System.currentTimeMillis()).apply();
        AppEntry selected = selectedApp(slot);
        if (selected == null) {
            Toast.makeText(this, "Сначала выбери читалку", Toast.LENGTH_SHORT).show();
            showAppPicker(slot, null);
        } else {
            Uri book = savedBook(slot);
            if (book != null && prefs.getBoolean("direct_" + slot, false)) {
                launchPinnedBook(slot, selected, book);
            } else {
                launchApp(selected);
            }
        }
    }

    private Uri savedBook(int slot) {
        String saved = prefs.getString("book_" + slot, null);
        try { return saved == null ? null : Uri.parse(saved); }
        catch (Exception e) { return null; }
    }

    private void launchPinnedBook(int slot, AppEntry app, Uri uri) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, getBookMime(prefs.getString("book_name_" + slot, "")));
            view.setPackage(app.component.getPackageName());
            view.setClipData(ClipData.newUri(getContentResolver(), "book", uri));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Читалка не открыла выбранный файл — запускаю её обычным способом", Toast.LENGTH_LONG).show();
            launchApp(app);
        }
    }

    private String getBookMime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".epub")) return "application/epub+zip";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".fb2") || n.endsWith(".fb2.zip")) return "application/x-fictionbook+xml";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private void openFiles() {
        AppEntry manager = selectedApp(2);
        if (manager != null) { launchApp(manager); return; }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        try {
            startActivityForResult(picker, PICK_FILE_FROM_STORAGE);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Выбери файловый менеджер в настройках книг", Toast.LENGTH_LONG).show();
            showAppPicker(2, null);
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

        LinearLayout spacer = vertical();
        body.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView footer = text("BOOX DUO   ·   E-INK EDITION   ·   0.6", 10, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        body.addView(footer, margins(-1, -2, 0, 23, 0, 0));
        showPage(scroll, 0);
    }


    // Four independently rendered sections. The same bottom navigation persists
    // on every section, including after returning from a reader application.
    private void showTab(int tab) {
        if (tab == 0) renderHome();
        else if (tab == 1) renderFileTab();
        else if (tab == 2) renderAppTab();
        else renderSettingsTab();
    }

    private void showPage(View content, int tab) {
        activeTab = tab;
        LinearLayout root = vertical();
        root.setBackgroundColor(PAPER);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(separator(), margins(-1, 1, 0, 0, 0, 0));
        LinearLayout navigation = horizontal();
        navigation.setPadding(dp(3), dp(3), dp(3), dp(3));
        String[] labels = {"Книги", "Файлы", "Приложения", "Настройки"};
        int[] icons = { android.R.drawable.ic_menu_agenda,
                android.R.drawable.ic_menu_save, android.R.drawable.ic_menu_sort_by_size,
                android.R.drawable.ic_menu_preferences };
        for (int i = 0; i < 4; i++) {
            final int chosen = i;
            LinearLayout item = vertical();
            item.setGravity(Gravity.CENTER);
            if (tab == i) item.setBackground(panel(WASH, EDGE, 6));
            ImageView img = new ImageView(this);
            img.setImageResource(icons[i]);
            img.setColorFilter(tab == i ? INK : MUTED);
            item.addView(img, margins(22, 22, 0, 0, 0, 3));
            TextView label = text(labels[i], 10, tab == i ? INK : MUTED, tab == i);
            label.setGravity(Gravity.CENTER);
            item.addView(label, margins(-1, -2, 0, 0, 0, 0));
            item.setOnClickListener(v -> { if (activeTab != chosen) showTab(chosen); });
            navigation.addView(item, new LinearLayout.LayoutParams(0, dp(57), 1));
        }
        root.addView(navigation, margins(-1, 63, 0, 0, 0, 0));
        setContentView(root);
    }

    private ScrollView newSection(LinearLayout[] output) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(PAPER);
        LinearLayout body = vertical();
        body.setPadding(dp(16), dp(12), dp(16), dp(20));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        output[0] = body;
        return scroll;
    }

    private void sectionHeading(LinearLayout body, String title, String description) {
        body.addView(text(title, 25, INK, true), margins(-1, -2, 0, 0, 0, 4));
        body.addView(text(description, 12, MUTED, false), margins(-1, -2, 0, 0, 0, 14));
    }

    private void sectionButton(LinearLayout body, String name, Runnable action) {
        body.addView(simpleAction(name, action), margins(-1, 46, 0, 0, 0, 9));
    }

    private void askFilesAccess() {
        new AlertDialog.Builder(this)
            .setTitle("Доступ к файлам")
            .setMessage("Чтобы категории показывали реальные файлы и количество, оболочке нужен доступ к общей памяти устройства. Используется только для просмотра и открытия файлов. Ничего не удаляется и никуда не отправляется.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть разрешение", (d,w) -> {
                try {
                    Intent access = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    access.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(access);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    } catch (Exception ex) {
                        Toast.makeText(this, "На этой прошивке доступ недоступен. Выбери конкретную папку ниже.", Toast.LENGTH_LONG).show();
                    }
                }
            }).show();
    }

    private void scanStorage() {
        if (!FilesCatalog.canRead() || storageScanBusy) return;
        storageScanBusy = true;
        new Thread(() -> {
            FilesCatalog.Snapshot result = FilesCatalog.scan();
            runOnUiThread(() -> {
                storageSnapshot = result;
                storageScanBusy = false;
                if (!isFinishing() && activeTab == 1) renderFileTab();
            });
        }, "boox-file-index").start();
    }

    private void renderFileTab() {
        LinearLayout[] output = new LinearLayout[1];
        ScrollView scroll = newSection(output);
        LinearLayout body = output[0];
        sectionHeading(body, "Внутренняя память", "Категории, папки и файлы");
        try {
            StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
            long free = stat.getAvailableBytes();
            long total = stat.getTotalBytes();
            LinearLayout memory = vertical();
            memory.setPadding(dp(13), dp(11), dp(13), dp(11));
            memory.setBackground(panel(WASH, EDGE, 8));
            memory.addView(text("ПАМЯТЬ УСТРОЙСТВА", 11, MUTED, true), margins(-1, -2, 0, 0, 0, 5));
            memory.addView(text("Свободно " + Formatter.formatShortFileSize(this, free)
                + " из " + Formatter.formatShortFileSize(this, total), 15, INK, true),
                margins(-1, -2, 0, 0, 0, 0));
            body.addView(memory, margins(-1, -2, 0, 0, 0, 16));
        } catch (Exception ignored) { }

        if (FilesCatalog.canRead()) {
            if (storageSnapshot == null && !storageScanBusy) scanStorage();

            if (fileCategory == -1 && currentStorageFolder == null) {
                body.addView(text("КАТЕГОРИИ", 11, MUTED, true), margins(-1, -2, 0, 0, 0, 9));
                for (int row = 0; row < 3; row++) {
                    LinearLayout tiles = horizontal();
                    for (int col = 0; col < (row == 2 ? 2 : 3); col++) {
                        int index = row * 3 + col;
                        final int selected = index;
                        LinearLayout tile = vertical();
                        tile.setGravity(Gravity.CENTER);
                        tile.setBackground(panel(PAPER, EDGE, 6));
                        TextView symbol = text(FilesCatalog.SIGNS[index], 18, INK, true);
                        symbol.setGravity(Gravity.CENTER);
                        tile.addView(symbol, margins(-1, -2, 0, 0, 0, 4));
                        TextView label = text(FilesCatalog.LABELS[index], 11, INK, false);
                        label.setGravity(Gravity.CENTER);
                        tile.addView(label);
                        int count = storageSnapshot == null ? -1 : storageSnapshot.count(index);
                        TextView number = text(count < 0 ? "…" : String.valueOf(count), 10, MUTED, false);
                        number.setGravity(Gravity.CENTER);
                        tile.addView(number);
                        tile.setOnClickListener(v -> {
                            fileCategory = selected;
                            currentStorageFolder = null;
                            renderFileTab();
                        });
                        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(102), 1);
                        if (col > 0) p.leftMargin = dp(8);
                        tiles.addView(tile, p);
                    }
                    body.addView(tiles, margins(-1, -2, 0, 0, 0, 8));
                }
                body.addView(text("ПАПКИ", 11, MUTED, true), margins(-1, -2, 0, 15, 0, 8));
                sectionButton(body, "Все папки устройства", () -> {
                    currentStorageFolder = FilesCatalog.root();
                    fileCategory = -1;
                    renderFileTab();
                });
                File downloads = new File(FilesCatalog.root(), "Download");
                if (downloads.isDirectory()) {
                    sectionButton(body, "Открыть папку Download", () -> {
                        currentStorageFolder = downloads;
                        fileCategory = -1;
                        renderFileTab();
                    });
                }
                sectionButton(body, "Обновить количество файлов", () -> {
                    storageSnapshot = null;
                    scanStorage();
                    renderFileTab();
                });
                sectionButton(body, "Открыть родной файловый менеджер BOOX", this::openFiles);
            } else {
                final boolean byCategory = fileCategory >= 0;
                sectionButton(body, "← К категориям", () -> {
                    fileCategory = -1;
                    currentStorageFolder = null;
                    renderFileTab();
                });
                String title = byCategory ? FilesCatalog.LABELS[fileCategory] :
                        currentStorageFolder.getName().isEmpty() ? "Память" : currentStorageFolder.getName();
                body.addView(text(title, 19, INK, true), margins(-1, -2, 0, 9, 0, 6));
                if (!byCategory && !currentStorageFolder.equals(FilesCatalog.root())) {
                    sectionButton(body, "← На уровень выше", () -> {
                        File up = currentStorageFolder.getParentFile();
                        currentStorageFolder = up == null ? FilesCatalog.root() : up;
                        renderFileTab();
                    });
                }
                if (byCategory && storageSnapshot == null) {
                    body.addView(text("Индексирую файлы…", 12, MUTED, false));
                } else {
                    List<File> listing = byCategory
                            ? storageSnapshot.groups[fileCategory]
                            : FilesCatalog.directory(currentStorageFolder);
                    body.addView(text("Элементов: " + listing.size(), 12, MUTED, false),
                            margins(-1, -2, 0, 0, 0, 9));
                    int max = Math.min(listing.size(), 230);
                    for (int i=0; i<max; i++) {
                        File file = listing.get(i);
                        String name = file.isDirectory() ? "[Папка] " + file.getName() : file.getName();
                        TextView item = text(name, 13, INK, false);
                        item.setPadding(dp(12), dp(10), dp(12), dp(10));
                        item.setBackground(panel(PAPER, EDGE, 5));
                        item.setOnClickListener(v -> {
                            if (file.isDirectory()) {
                                currentStorageFolder = file;
                                fileCategory = -1;
                                renderFileTab();
                            } else {
                                openDocument(SharedFileProvider.uriFor(file), FilesCatalog.mime(file));
                            }
                        });
                        body.addView(item, margins(-1, -2, 0, 0, 0, 6));
                    }
                    if (listing.isEmpty()) body.addView(text("Здесь пока нет файлов", 12, MUTED, false));
                    if (listing.size() > max) body.addView(text("Показаны первые " + max + " файлов по дате или названию", 12, MUTED, false));
                }
            }
        } else {
            body.addView(text("Для отображения количества и списка файлов нужен доступ к общей памяти.", 13, INK, false),
                    margins(-1, -2, 0, 0, 0, 12));
            sectionButton(body, "Разрешить просмотр файлов", this::askFilesAccess);
            body.addView(text("Если прошивка BOOX не откроет разрешение, можно выбрать отдельную папку:", 12, MUTED, false),
                    margins(-1, -2, 0, 10, 0, 8));
            sectionButton(body, "Выбрать папку", this::selectFolder);
            sectionButton(body, "Родной файловый менеджер BOOX", this::openFiles);
            renderGrantedFolder(body);
        }
        showPage(scroll, 1);
    }

    private void renderGrantedFolder(LinearLayout body) {
        String tree = prefs.getString("browse_tree", null);
        if (tree == null) return;
        try {
            Uri treeUri = Uri.parse(tree);
            String folder = folderTrail.isEmpty() ? DocumentsContract.getTreeDocumentId(treeUri)
                    : folderTrail.get(folderTrail.size()-1);
            if (folderTrail.size()>1) {
                sectionButton(body, "← На уровень выше", () -> {
                    folderTrail.remove(folderTrail.size()-1);
                    renderFileTab();
                });
            }
            body.addView(text("ВЫБРАННАЯ ПАПКА", 12, INK, true));
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folder);
            ArrayList<FileItem> entries = new ArrayList<>();
            String[] fields = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE};
            try (Cursor c=getContentResolver().query(children, fields, null, null, null)) {
                if (c!=null) {
                    while (c.moveToNext() && entries.size()<300)
                        entries.add(new FileItem(c.getString(0),c.getString(1),c.getString(2)));
                }
            }
            Collections.sort(entries,(a,b)->{
                if(a.isFolder()!=b.isFolder()) return a.isFolder()?-1:1;
                return a.name.compareToIgnoreCase(b.name);
            });
            for (FileItem item:entries) {
                TextView line=text((item.isFolder() ? "[Папка] " : "") + item.name,13,INK,false);
                line.setPadding(dp(12),dp(10),dp(12),dp(10));
                line.setBackground(panel(PAPER,EDGE,5));
                line.setOnClickListener(v-> {
                    if (item.isFolder()) {
                        folderTrail.add(item.id); renderFileTab();
                    } else openDocument(DocumentsContract.buildDocumentUriUsingTree(treeUri,item.id),item.mime);
                });
                body.addView(line,margins(-1,-2,0,0,0,6));
            }
        } catch(Exception e) {
            body.addView(text("Выбранная папка недоступна. Выбери её заново.",12,MUTED,false));
        }
    }

    private static final class FileItem {
        final String id, name, mime;
        FileItem(String id, String name, String mime) {
            this.id=id;
            this.name=name == null ? "(без имени)" : name;
            this.mime=mime == null ? "*/*" : mime;
        }
        boolean isFolder() { return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime); }
    }

    private void openFileCategory(String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        try { startActivityForResult(intent, PICK_FILE_FROM_STORAGE); }
        catch (Exception e) { Toast.makeText(this, "Не удалось открыть файлы", Toast.LENGTH_SHORT).show(); }
    }

    private void selectFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_BROWSE_FOLDER); }
        catch (Exception e) { Toast.makeText(this, "Выбор папки не поддерживается", Toast.LENGTH_LONG).show(); }
    }

    private void openDocument(Uri uri, String mime) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.setClipData(ClipData.newUri(getContentResolver(), "book", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(intent, "Открыть файл")); }
        catch (Exception e) { Toast.makeText(this, "Не найдено приложение для этого файла", Toast.LENGTH_LONG).show(); }
    }

    private void renderAppTab() {
        LinearLayout[] output = new LinearLayout[1];
        ScrollView scroll = newSection(output);
        LinearLayout body = output[0];
        sectionHeading(body, "Приложения", "Нажми для запуска · удерживай для управления");

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(INK);
        search.setHint("Поиск приложений");
        search.setBackground(panel(PAPER, EDGE, 7));
        search.setPadding(dp(12), 0, dp(12), 0);
        body.addView(search, margins(-1, 48, 0, 0, 0, 13));

        LinearLayout grid = vertical();
        body.addView(grid);

        Runnable rebuild = () -> {
            grid.removeAllViews();
            String filter = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            ArrayList<AppEntry> visible = new ArrayList<>();
            for (AppEntry entry : apps) {
                if (entry.description().toLowerCase(Locale.ROOT).contains(filter)) {
                    visible.add(entry);
                }
            }
            if (visible.isEmpty()) {
                grid.addView(text("Приложения не найдены", 13, MUTED, false),
                        margins(-1, -2, 0, 4, 0, 0));
                return;
            }

            PackageManager manager = getPackageManager();
            final int columns = 3;
            for (int offset = 0; offset < visible.size(); offset += columns) {
                LinearLayout line = horizontal();
                for (int col = 0; col < columns; col++) {
                    int index = offset + col;
                    if (index >= visible.size()) {
                        line.addView(new View(this), new LinearLayout.LayoutParams(0, dp(110), 1));
                        continue;
                    }
                    AppEntry app = visible.get(index);
                    LinearLayout tile = vertical();
                    tile.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                    tile.setPadding(dp(4), dp(9), dp(4), dp(4));
                    tile.setBackground(panel(PAPER, EDGE, 7));

                    ImageView appIcon = new ImageView(this);
                    appIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    try {
                        appIcon.setImageDrawable(manager.getActivityIcon(app.component));
                    } catch (PackageManager.NameNotFoundException ex) {
                        try {
                            appIcon.setImageDrawable(manager.getApplicationIcon(app.component.getPackageName()));
                        } catch (PackageManager.NameNotFoundException ignored) {
                            appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
                        }
                    }
                    LinearLayout.LayoutParams iconLayout =
                            new LinearLayout.LayoutParams(dp(46), dp(46));
                    iconLayout.gravity = Gravity.CENTER_HORIZONTAL;
                    iconLayout.bottomMargin = dp(7);
                    tile.addView(appIcon, iconLayout);

                    TextView title = text(app.label, 12, INK, false);
                    title.setGravity(Gravity.CENTER);
                    title.setMaxLines(2);
                    title.setEllipsize(TextUtils.TruncateAt.END);
                    tile.addView(title, new LinearLayout.LayoutParams(-1, -2));

                    tile.setOnClickListener(v -> launchApp(app));
                    tile.setOnLongClickListener(v -> {
                        appDetails(app.component.getPackageName());
                        return true;
                    });

                    LinearLayout.LayoutParams layout =
                            new LinearLayout.LayoutParams(0, dp(106), 1);
                    if (col > 0) layout.leftMargin = dp(8);
                    line.addView(tile, layout);
                }
                grid.addView(line, margins(-1, -2, 0, 0, 0, 8));
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                rebuild.run();
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        rebuild.run();
        showPage(scroll, 2);
    }

    private void appDetails(String packageName) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "BOOX не открыл системные сведения о приложении", Toast.LENGTH_LONG).show();
        }
    }

    private void renderSettingsTab() {
        LinearLayout[] output = new LinearLayout[1];
        ScrollView scroll = newSection(output);
        LinearLayout body = output[0];
        sectionHeading(body, "Настройки", "Ваша домашняя библиотека");
        body.addView(text("СИСТЕМНАЯ БИБЛИОТЕКА BOOX", 13, INK, true),
                margins(-1, -2, 0, 0, 0, 5));
        String booxStatus = booxLoading ? "Проверяю доступ…" : booxLibrary == null
                ? "Ещё не проверено" : booxLibrary.details;
        body.addView(text(booxStatus, 12, MUTED, false),
                margins(-1, -2, 0, 0, 0, 7));
        sectionButton(body, "Проверить библиотеку BOOX (только чтение)", () -> refreshBooxLibrary(true));
        body.addView(text("Если библиотека доступна, привяжи книгу Анны к её записи: тогда прогресс и последнее открытие будут обновляться при возвращении в оболочку.", 12, MUTED, false),
                margins(-1, -2, 0, 0, 0, 8));
        for (int slot = 0; slot < 2; slot++) addSettingsForSlot(body, slot);
        body.addView(text("ФАЙЛОВЫЙ МЕНЕДЖЕР", 13, INK, true),
                margins(-1, -2, 0, 12, 0, 6));
        AppEntry fileManager = selectedApp(2);
        body.addView(text(fileManager == null ? "Не выбран" : fileManager.label, 12, MUTED, false),
                margins(-1, -2, 0, 0, 0, 6));
        sectionButton(body, "Выбрать файловый менеджер", () -> showAppPicker(2, null));
        sectionButton(body, "Выбрать домашний экран Android", this::openHomeSettings);
        sectionButton(body, "Сведения о приложении «Наши книги»", () -> appDetails(getPackageName()));
        sectionButton(body, "Удалить только оболочку «Наши книги»", () ->
                new AlertDialog.Builder(this)
                    .setTitle("Удалить оболочку?")
                    .setMessage("ReadEra, AlReaderX и ваши книги останутся на устройстве.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Открыть удаление", (d,w) -> {
                        try {
                            Intent intent=new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception e) { appDetails(getPackageName()); }
                    }).show());
        body.addView(text("BOOX DUO 0.6 · Android 11 · Офлайн", 11, MUTED, false),
                margins(-1, -2, 0, 8, 0, 5));
        showPage(scroll, 3);
    }

    @Override public void onBackPressed() {
        if (activeTab == 1 && (fileCategory >= 0 || currentStorageFolder != null)) {
            if (currentStorageFolder != null && !currentStorageFolder.equals(FilesCatalog.root())) {
                currentStorageFolder = currentStorageFolder.getParentFile();
            } else { currentStorageFolder = null; fileCategory = -1; }
            renderFileTab();
        } else if (activeTab == 1 && folderTrail.size() > 1) {
            folderTrail.remove(folderTrail.size() - 1);
            renderFileTab();
        } else if (activeTab != 0) {
            showTab(0);
        } else {
            super.onBackPressed();
        }
    }

    private void addShortcut(LinearLayout parent, String title, Runnable action, boolean first) {
        TextView button = text(title, 12, INK, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(panel(WASH, EDGE, 8));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        if (first) params.rightMargin = dp(7);
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
        Bitmap bitmap = coverUri == null ? null : decodeCover(coverUri);
        if (bitmap == null) {
            File extracted = getFileStreamPath("auto_cover_" + slot + ".jpg");
            if (extracted.exists()) bitmap = BitmapFactory.decodeFile(extracted.getAbsolutePath());
        }
        if (bitmap != null) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(bitmap);
            cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
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

        BooxLibrary.Book nativeBook = linkedBooxBook(slot);
        int current = nativeBook != null && nativeBook.totalPages > 0
                ? nativeBook.currentPage : prefs.getInt("page_" + slot, 0);
        int total = nativeBook != null && nativeBook.totalPages > 0
                ? nativeBook.totalPages : prefs.getInt("total_" + slot, 0);
        if (current > 0 && total >= current) {
            int percent = Math.min(100, Math.round(100f * current / total));
            String source = nativeBook != null && nativeBook.totalPages > 0
                    ? "BOOX" : "вручную";
            info.addView(text("Страница " + current + "/" + total + " · " + percent + "% (" + source + ")",
                    11, MUTED, false), margins(-1, -2, 0, 3, 0, 4));
            LinearLayout progress = horizontal();
            progress.setBackgroundColor(WASH);
            View filled = new View(this);
            filled.setBackgroundColor(INK);
            progress.addView(filled, new LinearLayout.LayoutParams(0, dp(4), percent));
            progress.addView(new View(this), new LinearLayout.LayoutParams(0, dp(4), 100-percent));
            info.addView(progress, margins(-1, 4, 0, 0, 0, 5));
        } else if (nativeBook != null && !nativeBook.progress.isEmpty()) {
            info.addView(text("BOOX: " + nativeBook.progress, 11, MUTED, false),
                    margins(-1, -2, 0, 4, 0, 5));
        }
        if (nativeBook != null && nativeBook.lastAccess > 0) {
            String when = new SimpleDateFormat("dd.MM.yyyy HH:mm", new Locale("ru", "RU"))
                    .format(new Date(nativeBook.lastAccess));
            info.addView(text("BOOX · Последнее открытие: " + when, 10, MUTED, false),
                    margins(-1, -2, 0, 1, 0, 5));
        } else {
            long lastLaunch = prefs.getLong("last_launch_" + slot, 0);
            if (lastLaunch > 0) {
                String when = new SimpleDateFormat("dd.MM.yyyy HH:mm", new Locale("ru", "RU"))
                        .format(new Date(lastLaunch));
                info.addView(text("Запуск из оболочки: " + when, 10, MUTED, false),
                        margins(-1, -2, 0, 1, 0, 5));
            }
        }

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
        content.addView(text("Названия и обложки можно настроить вручную или выбрать сам файл книги для извлечения обложки.", 13, MUTED, false),
                margins(-1, -2, 0, 0, 0, 12));
        for (int slot = 0; slot < 2; slot++) addSettingsForSlot(content, slot);
        AppEntry manager = selectedApp(2);
        content.addView(text("ФАЙЛОВЫЙ МЕНЕДЖЕР", 14, INK, true), margins(-1, -2, 0, 14, 0, 5));
        content.addView(text(manager == null ? "Не найден — откроется системный выбор файла" : manager.label, 12, MUTED, false),
                margins(-1, -2, 0, 0, 0, 7));
        content.addView(simpleAction("Выбрать приложение для кнопки «Файлы»", () -> showAppPicker(2, null)),
                margins(-1, 43, 0, 0, 0, 7));
        content.addView(simpleAction("Выбрать домашний экран Android", this::openHomeSettings),
                margins(-1, 47, 0, 14, 0, 4));
        content.addView(text("Если BOOX не разрешит заменить главный экран, запускай «Наши книги» из списка приложений. Это безопасно и без root.",
                12, MUTED, false), margins(-1, -2, 0, 8, 0, 5));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Настройки наших книг")
                .setView(scroll)
                .setPositiveButton("Готово", (d, w) -> showTab(activeTab))
                .create();
        dialog.setOnDismissListener(d -> showTab(activeTab));
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
        parent.addView(simpleAction("Закрепить файл книги (EPUB, FB2, PDF)", () -> pickBook(slot)),
                margins(-1, 43, 0, 0, 0, 5));
        TextView direct = text("Открывать закреплённую книгу сразу: "
                + (prefs.getBoolean("direct_" + slot, false) ? "ДА" : "НЕТ"), 13, INK, false);
        direct.setGravity(Gravity.CENTER);
        direct.setBackground(panel(WASH, EDGE, 7));
        direct.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean("direct_" + slot, false);
            prefs.edit().putBoolean("direct_" + slot, next).apply();
            direct.setText("Открывать закреплённую книгу сразу: " + (next ? "ДА" : "НЕТ"));
        });
        parent.addView(direct, margins(-1, 43, 0, 0, 0, 5));
        parent.addView(simpleAction("Привязать книгу из библиотеки BOOX", () -> showBooxBookPicker(slot)),
                margins(-1, 43, 0, 0, 0, 5));
        BooxLibrary.Book nativeBook = linkedBooxBook(slot);
        parent.addView(text(nativeBook != null ? "Источник прогресса: BOOX · " + nativeBook.name
                        : "Источник прогресса: вручную / не подключён",
                11, MUTED, false), margins(-1, -2, 0, 0, 0, 6));
        parent.addView(simpleAction("Указать страницы и прогресс вручную", () -> editProgress(slot)),
                margins(-1, 43, 0, 0, 0, 5));
        parent.addView(simpleAction("Поставить свою обложку (картинка)", () -> pickCover(slot)),
                margins(-1, 43, 0, 0, 0, 5));
        parent.addView(simpleAction("Убрать обложку", () -> {
            prefs.edit().remove("cover_" + slot).apply();
            getFileStreamPath("auto_cover_" + slot + ".jpg").delete();
            Toast.makeText(this, "Обложка удалена", Toast.LENGTH_SHORT).show();
        }), margins(-1, 43, 0, 0, 0, 4));
        parent.addView(separator(), margins(-1, 1, 0, 13, 0, 0));
    }

    private void editProgress(int slot) {
        LinearLayout fields = vertical();
        fields.setPadding(dp(22), dp(5), dp(22), 0);
        EditText current = new EditText(this);
        current.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        current.setSingleLine(true);
        current.setHint("Страница сейчас");
        int p = prefs.getInt("page_" + slot, 0);
        if (p > 0) current.setText(String.valueOf(p));
        EditText total = new EditText(this);
        total.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        total.setSingleLine(true);
        total.setHint("Всего страниц");
        int t = prefs.getInt("total_" + slot, 0);
        if (t > 0) total.setText(String.valueOf(t));
        fields.addView(current, margins(-1, 47, 0, 0, 0, 8));
        fields.addView(total, margins(-1, 47, 0, 0, 0, 8));
        fields.addView(text("Эти значения не синхронизируются с ReadEra и AlReaderX.",
                12, MUTED, false), margins(-1, -2, 0, 0, 0, 3));
        new AlertDialog.Builder(this)
                .setTitle("Прогресс чтения")
                .setView(fields)
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Очистить", (d,w) -> {
                    prefs.edit().remove("page_" + slot).remove("total_" + slot).apply();
                    showTab(activeTab);
                })
                .setPositiveButton("Сохранить", (d,w) -> {
                    try {
                        int now = Integer.parseInt(current.getText().toString().trim());
                        int all = Integer.parseInt(total.getText().toString().trim());
                        if (now < 1 || all < now) throw new NumberFormatException();
                        prefs.edit().putInt("page_" + slot, now).putInt("total_" + slot, all).apply();
                        showTab(activeTab);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Укажи две положительные страницы (текущая не больше общей)", Toast.LENGTH_LONG).show();
                    }
                }).show();
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
                .setTitle(slot < 0 ? "Открыть приложение" : slot == 2 ? "Файловый менеджер" : "Выбери свою читалку")
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
                showTab(activeTab);
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

    private void pickBook(int slot) {
        Intent select = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        select.addCategory(Intent.CATEGORY_OPENABLE);
        select.setType("*/*");
        select.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(select, PICK_BOOK_BASE + slot); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Не удалось открыть выбор книги", Toast.LENGTH_LONG).show();
        }
    }

    private String selectedFilename(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() == null ? "Книга" : uri.getLastPathSegment();
    }

    private void onBookSelected(int slot, Intent data) {
        Uri uri = data.getData();
        String filename = selectedFilename(uri);
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        String title = prefs.getString("title_" + slot, "");
        SharedPreferences.Editor edit = prefs.edit()
                .putString("book_" + slot, uri.toString())
                .putString("book_name_" + slot, filename)
                .putBoolean("direct_" + slot, true);
        if (title.length() == 0 || title.equals("Моя книга") || title.equals("Книга Анны")) {
            edit.putString("title_" + slot, filename.replaceFirst("(?i)\\.(epub|fb2|zip|pdf|txt)$", ""));
        }
        edit.apply();
        Toast.makeText(this, "Книга закреплена. Ищу обложку…", Toast.LENGTH_SHORT).show();
        showTab(activeTab);
        new Thread(() -> {
            Bitmap cover = BookPreview.extract(getApplicationContext(), uri, filename);
            boolean saved = false;
            if (cover != null) {
                try (FileOutputStream out = openFileOutput("auto_cover_" + slot + ".jpg", MODE_PRIVATE)) {
                    saved = cover.compress(Bitmap.CompressFormat.JPEG, 85, out);
                } catch (Exception ignored) { }
                cover.recycle();
            }
            final boolean found = saved;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                showTab(activeTab);
                Toast.makeText(this, found ? "Обложка найдена" :
                        "Обложка не найдена. Можно выбрать картинку вручную.", Toast.LENGTH_LONG).show();
            });
        }).start();
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == PICK_BROWSE_FOLDER) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                prefs.edit().putString("browse_tree", uri.toString()).apply();
                folderTrail.clear();
                folderTrail.add(DocumentsContract.getTreeDocumentId(uri));
                renderFileTab();
            } catch (Exception e) {
                Toast.makeText(this, "Нет доступа к выбранной папке", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == PICK_FILE_FROM_STORAGE) {
            try {
                String mime = getContentResolver().getType(data.getData());
                openDocument(data.getData(), mime == null ? "*/*" : mime);
            } catch (Exception ignored) {
                Toast.makeText(this, "Файл выбран. Для управления файлами назначь файловый менеджер.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == PICK_BOOK_BASE || requestCode == PICK_BOOK_BASE + 1) {
            onBookSelected(requestCode - PICK_BOOK_BASE, data);
            return;
        }
        if (requestCode != PICK_COVER_BASE && requestCode != PICK_COVER_BASE + 1) return;
        int slot = requestCode - PICK_COVER_BASE;
        Uri uri = data.getData();
        try {
            int readFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, readFlags);
        } catch (SecurityException ignored) {
            // Some document providers grant session-only access; no crash.
        }
        prefs.edit().putString("cover_" + slot, uri.toString()).apply();
        showTab(activeTab);
    }

    private void openHomeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "BOOX не открыл выбор главного экрана. Запускай оболочку как приложение.", Toast.LENGTH_LONG).show();
        }
    }
}
