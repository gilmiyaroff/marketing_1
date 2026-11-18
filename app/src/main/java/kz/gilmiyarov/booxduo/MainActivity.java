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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.BatteryManager;
import android.os.Build;
import android.content.IntentFilter;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.os.Environment;
import android.os.StatFs;
import android.util.LruCache;
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
import android.widget.HorizontalScrollView;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private boolean readingSettingsOpen = false;
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
    private int allBooksScrollX = 0;
    private int allBooksVerticalScrollY = 0;
    private final ExecutorService thumbnailWorker = Executors.newSingleThreadExecutor();
    private final Set<String> coverAttempted = new HashSet<>();
    private final LruCache<String, Bitmap> thumbnailCache =
            new LruCache<String, Bitmap>(10 * 1024 * 1024) {
                @Override protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getByteCount();
                }
            };
    private int pendingBookPicker = -1;

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
                if (pendingBookPicker >= 0) {
                    int slot = pendingBookPicker;
                    pendingBookPicker = -1;
                    showBooxBookPicker(slot);
                }
            });
        }, "boox-library-read-only").start();
    }

    private static String bookStem(String value) {
        if (value == null) return "";
        String text = value.trim().replace('\\', '/');
        text = text.substring(text.lastIndexOf('/') + 1);
        text = text.replaceFirst("(?i)\\.(fb2\\.zip|epub|fb2|pdf|mobi|azw3|txt|djvu|docx?)$", "");
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private BooxLibrary.Book linkedBooxBook(int slot) {
        if (booxLibrary == null || !"ok".equals(booxLibrary.status)) return null;
        // A link made in v0.5/v0.6 remains the first and most reliable choice.
        BooxLibrary.Book chosen = BooxLibrary.match(booxLibrary,
                prefs.getString("boox_link_" + slot, null));
        if (chosen != null) return chosen;

        // Auto-link only by an exact, unique filename or previously saved title.
        // We never use the latest *other person's* book as a guess.
        String preferredFile = bookStem(prefs.getString("book_name_" + slot, ""));
        String preferredTitle = bookStem(prefs.getString("title_" + slot, ""));
        if (preferredTitle.equals("моя книга") || preferredTitle.equals("книга анны")) {
            preferredTitle = "";
        }
        if (preferredFile.isEmpty() && preferredTitle.isEmpty()) return null;
        BooxLibrary.Book matched = null;
        for (BooxLibrary.Book book : booxLibrary.books) {
            String candidate = bookStem(!book.path.isEmpty() ? book.path : book.name);
            String title = bookStem(book.name);
            boolean exactFile = !preferredFile.isEmpty() &&
                    (preferredFile.equals(candidate) || preferredFile.equals(title));
            boolean exactTitle = !preferredTitle.isEmpty() &&
                    (preferredTitle.equals(title) || preferredTitle.equals(candidate));
            if (!exactFile && !exactTitle) continue;
            if (matched != null && !matched.key().equals(book.key())) return null;
            matched = book;
        }
        return matched;
    }

    private void showBooxBookPicker(int slot) {
        if (booxLibrary == null) {
            pendingBookPicker = slot;
            Toast.makeText(this,"Загружаю книги BOOX…",Toast.LENGTH_SHORT).show();
            refreshBooxLibrary(false);
            return;
        }
        if (!"ok".equals(booxLibrary.status)) {
            new AlertDialog.Builder(this)
                .setTitle("Библиотека недоступна")
                .setMessage(booxLibrary.details)
                .setPositiveButton("Выбрать файл", (d,w)->pickBook(slot))
                .setNeutralButton("Повторить", (d,w)->{
                    pendingBookPicker=slot;
                    refreshBooxLibrary(false);
                })
                .setNegativeButton("Отмена",null).show();
            return;
        }
        if (booxLibrary.books.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("Библиотека пуста")
                .setMessage("Можно выбрать файл книги напрямую.")
                .setPositiveButton("Выбрать файл",(d,w)->pickBook(slot))
                .setNegativeButton("Отмена",null).show();
            return;
        }
        BookPickerDialog.show(this,booxLibrary.books,new BookPickerDialog.Listener(){
            @Override public void select(BooxLibrary.Book book) {
                bindBookToCard(slot,book);
            }
            @Override public void unlink() {
                prefs.edit().remove("boox_link_" + slot)
                    .remove("bound_book_key_" + slot)
                    .remove("book_" + slot)
                    .remove("book_name_" + slot)
                    .remove("title_" + slot)
                    .remove("page_" + slot).remove("total_" + slot).apply();
                showTab(activeTab);
            }
            @Override public void browseFile() {
                pickBook(slot);
            }
        });
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

    // The three family slots share ONE reading app. 'target_2' remains the
    // existing file manager preference from v0.10, and is never reused here.
    private AppEntry familyReader() {
        String saved = prefs.getString("family_reader", null);
        if (saved != null) {
            ComponentName selected = ComponentName.unflattenFromString(saved);
            if (selected != null) {
                for (AppEntry e : apps) if (e.component.equals(selected)) return e;
            }
        }
        AppEntry best = null;
        int bestScore = 0;
        for (AppEntry candidate : apps) {
            String value = (candidate.label + " " + candidate.component.getPackageName())
                    .toLowerCase(Locale.ROOT);
            int score = 0;
            if (value.contains("alreaderx pro")) score = 100;
            else if (value.contains("alreaderx")) score = 90;
            else if (value.contains("alreader")) score = 70;
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best != null) {
            prefs.edit().putString("family_reader", best.component.flattenToString()).apply();
        }
        return best;
    }

    private void showFamilyReaderPicker() {
        if (apps.isEmpty()) {
            Toast.makeText(this, "Установленные приложения не найдены", Toast.LENGTH_LONG).show();
            return;
        }
        // AlReaderX is first in the list; every other installed app remains
        // selectable in case the firmware uses a custom icon/label.
        ArrayList<AppEntry> choices = new ArrayList<>(apps);
        Collections.sort(choices, (a,b) -> {
            boolean aa = (a.label + a.component.getPackageName())
                    .toLowerCase(Locale.ROOT).contains("alreader");
            boolean bb = (b.label + b.component.getPackageName())
                    .toLowerCase(Locale.ROOT).contains("alreader");
            if (aa != bb) return aa ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        String[] labels = new String[choices.size()];
        for (int i=0;i<choices.size();i++) labels[i] = choices.get(i).label;
        new AlertDialog.Builder(this)
            .setTitle("Одна читалка для всех")
            .setMessage("Это приложение будет открывать книги Руслана, Анны и Амелии")
            .setItems(labels, (d,which)->{
                prefs.edit().putString("family_reader",
                        choices.get(which).component.flattenToString()).apply();
                showTab(activeTab);
            })
            .setNegativeButton("Отмена", null)
            .show();
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

    // A BOOX metadata link is NOT an Android file launch. Do not confuse a
    // selected title with ReadEra's "last opened" history.
    private void openReader(int slot) {
        AppEntry reader = familyReader();
        if (reader == null) {
            showFamilyReaderPicker();
            return;
        }
        Uri file = savedBook(slot);
        String selectedKey = prefs.getString("boox_link_" + slot, null);
        String boundKey = prefs.getString("bound_book_key_" + slot, null);

        // An older APK may have saved a URI for a different book. Never use it
        // once the book on the card changes.
        if (file == null || (selectedKey != null && !selectedKey.equals(boundKey))) {
            BooxLibrary.Book linked = linkedBooxBook(slot);
            if (linked != null && tryBindAccessibleFile(slot, linked)) {
                file = savedBook(slot);
            }
        }
        if (file == null || (selectedKey != null
                && !selectedKey.equals(prefs.getString("bound_book_key_" + slot, null)))) {
            showSelectExactBookDialog(slot);
            return;
        }
        prefs.edit().putLong("last_launch_" + slot, System.currentTimeMillis()).apply();
        launchPinnedBook(slot, reader, file);
    }

    private Uri savedBook(int slot) {
        String saved = prefs.getString("book_" + slot, null);
        try { return saved == null ? null : Uri.parse(saved); }
        catch (Exception e) { return null; }
    }

    private boolean tryBindAccessibleFile(int slot, BooxLibrary.Book book) {
        if (book == null || book.path == null || book.path.trim().isEmpty()) return false;
        try {
            File file = new File(book.path);
            if (!file.isFile() || !file.canRead()) return false;
            prefs.edit()
                .putString("book_" + slot, SharedFileProvider.uriFor(file).toString())
                .putString("book_name_" + slot, file.getName())
                .putString("bound_book_key_" + slot, book.key())
                .putBoolean("direct_" + slot, true)
                .apply();
            return true;
        } catch (Exception ignored) { return false; }
    }

    private void showSelectExactBookDialog(int slot) {
        BooxLibrary.Book book = linkedBooxBook(slot);
        String name = book == null
                ? prefs.getString("title_" + slot, "выбранная книга") : book.name;
        new AlertDialog.Builder(this)
            .setTitle("Нужно выбрать файл книги")
            .setMessage("Для «" + name + "» пока не привязан доступный файл. "
                + "Если просто запустить читалку, она может открыть последнюю книгу другого человека. "
                + "Выбери EPUB, FB2 или PDF именно этой книги.")
            .setPositiveButton("Выбрать файл", (d,w) -> pickBook(slot))
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void launchPinnedBook(int slot, AppEntry app, Uri uri) {
        final String filename = prefs.getString("book_name_" + slot, "");
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, getBookMime(filename));
        view.setPackage(app.component.getPackageName());
        view.setClipData(ClipData.newUri(getContentResolver(), filename, uri));
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException | SecurityException e) {
            // Never silently launch the reader's last book: it might be a
            // different title from the one displayed on the card.
            new AlertDialog.Builder(this)
                .setTitle("Читалка не открыла файл")
                .setMessage("«" + filename + "» не удалось передать в "
                    + app.label + ". Можно открыть этот же файл через другую читалку.")
                .setPositiveButton("Выбрать читалку", (d,w) -> {
                    Intent anyReader = new Intent(Intent.ACTION_VIEW);
                    anyReader.setDataAndType(uri, getBookMime(filename));
                    anyReader.setClipData(ClipData.newUri(getContentResolver(), filename, uri));
                    anyReader.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(Intent.createChooser(anyReader, "Открыть именно эту книгу"));
                    } catch (Exception exc) {
                        Toast.makeText(this, "Нет приложения, способного открыть файл",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("Выбрать файл заново", (d,w) -> pickBook(slot))
                .setNegativeButton("Отмена", null)
                .show();
        }
    }

    private void bindBookToCard(int slot, BooxLibrary.Book book) {
        // Clear the previous book's URI and manual page count at the same time
        // as changing the title. Keeps card + launch intent consistent.
        prefs.edit()
            .putString("boox_link_" + slot, book.key())
            .putString("title_" + slot, book.name)
            .remove("book_" + slot)
            .remove("book_name_" + slot)
            .remove("bound_book_key_" + slot)
            .remove("page_" + slot)
            .remove("total_" + slot)
            .remove("last_launch_" + slot)
            .putBoolean("direct_" + slot, true)
            .apply();
        showTab(activeTab);
        if (!tryBindAccessibleFile(slot, book)) {
            showSelectExactBookDialog(slot);
        } else {
            Toast.makeText(this, "Книга выбрана — карточка откроет именно этот файл",
                    Toast.LENGTH_LONG).show();
            showTab(activeTab);
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

    // Two equal E-Ink cards. Illustrations are transparent vector art, converted at
    // build time; backgrounds and all Russian text are drawn natively by Android.
    private void renderHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(PAPER);

        LinearLayout body = vertical();
        body.setPadding(dp(12), dp(8), dp(12), dp(12));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        body.addView(text("НАША БИБЛИОТЕКА", 10, MUTED, true), margins(-1, -2, 0, 0, 0, 2));
        body.addView(text("Наши книги", 23, INK, true), margins(-1, -2, 0, 0, 0, 1));
        String date = new SimpleDateFormat("EEEE, d MMMM", new Locale("ru", "RU")).format(new Date());
        body.addView(text(date, 11, MUTED, false), margins(-1, -2, 0, 0, 0, 8));

        // The three cards share the same section-cover and metadata layout.
        body.addView(readerCard(0, "РУСЛАНА", "", ""), margins(-1, 136, 0, 0, 0, 7));
        body.addView(readerCard(1, "АННЫ", "", ""), margins(-1, 136, 0, 0, 0, 9));
        body.addView(readerCard(2, "АМЕЛИИ", "", ""), margins(-1, 136, 0, 0, 0, 6));

        

        addAllBooksCarousel(body);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> allBooksVerticalScrollY = y);
        scroll.post(() -> scroll.scrollTo(0, allBooksVerticalScrollY));
        showPage(scroll, 0);
    }

    // Horizontal, continuously swipeable shelf of all titles exposed by BOOX.
    // The first version intentionally avoids third-party libraries and animations.
    private void addAllBooksCarousel(LinearLayout body) {
        body.addView(separator(), margins(-1, 1, 0, 15, 0, 15));
        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("ВСЕ КНИГИ", 14, INK, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        if (booxLibrary != null && "ok".equals(booxLibrary.status)) {
            TextView total = text(booxLibrary.books.size() + "  ·  ЛИСТАЙ →", 10, MUTED, false);
            heading.addView(total);
        } else {
            TextView loading = text(booxLoading ? "ЗАГРУЗКА" : "ОБНОВИТЬ", 10, MUTED, true);
            loading.setPadding(dp(8), dp(7), 0, dp(7));
            loading.setOnClickListener(v -> refreshBooxLibrary(true));
            heading.addView(loading);
        }
        body.addView(heading, margins(-1, -2, 0, 0, 0, 9));

        if (booxLibrary == null || !"ok".equals(booxLibrary.status)) {
            String message = booxLibrary == null || booxLoading
                    ? "Загружаю библиотеку BOOX…"
                    : "Библиотека недоступна на этой прошивке";
            TextView status = text(message, 12, MUTED, false);
            status.setPadding(dp(12), dp(17), dp(12), dp(17));
            status.setBackground(panel(WASH, EDGE, 6));
            body.addView(status, margins(-1, -2, 0, 0, 0, 7));
            return;
        }
        // Deduplicate identical local paths, but preserve distinct books that
        // merely happen to share a title.
        Map<String, BooxLibrary.Book> unique = new LinkedHashMap<>();
        for (BooxLibrary.Book item : booxLibrary.books) {
            String raw = item.path == null ? "" : item.path.trim();
            String identity = raw.isEmpty() ? item.key() : "path:" + raw.toLowerCase(Locale.ROOT);
            if (!unique.containsKey(identity)) unique.put(identity, item);
        }
        if (unique.isEmpty()) {
            body.addView(text("Пока нет книг в системной библиотеке", 13, MUTED, false));
            return;
        }

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setFillViewport(false);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setSmoothScrollingEnabled(false);
        horizontal.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout strip = horizontal();
        strip.setGravity(Gravity.TOP);
        strip.setPadding(0, dp(2), dp(8), dp(6));
        horizontal.addView(strip, new HorizontalScrollView.LayoutParams(-2, -2));

        ArrayList<CarouselThumbnail> thumbs = new ArrayList<>();
        for (BooxLibrary.Book item : unique.values()) {
            LinearLayout tile = vertical();
            tile.setPadding(dp(7), dp(7), dp(7), dp(6));
            tile.setBackground(panel(PAPER, EDGE, 6));
            LinearLayout.LayoutParams tileLayout = margins(130, 188, 0, 0, 9, 0);
            strip.addView(tile, tileLayout);

            FrameLayout preview = new FrameLayout(this);
            preview.setBackground(panel(WASH, EDGE, 4));
            preview.setClipToOutline(true);
            tile.addView(preview, margins(-1, 112, 0, 0, 0, 7));

            TextView fallback = text(bookLabel(item.name), 12, INK, true);
            fallback.setGravity(Gravity.CENTER);
            fallback.setPadding(dp(8), dp(8), dp(8), dp(8));
            fallback.setMaxLines(4);
            fallback.setEllipsize(TextUtils.TruncateAt.END);
            preview.addView(fallback, new FrameLayout.LayoutParams(-1, -1));

            ImageView thumbnail = new ImageView(this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setVisibility(View.GONE);
            preview.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));

            TextView name = text(item.name.isEmpty() ? bookLabel(item.path) : item.name,
                    11, INK, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            tile.addView(name, margins(-1, -2, 0, 0, 0, 3));

            if (item.hasUsableProgress()) {
                TextView progress = text(item.percent() + "%", 10, MUTED, false);
                tile.addView(progress);
            } else if (item.lastAccess > 0) {
                String recent = new SimpleDateFormat("dd.MM.yy", Locale.getDefault())
                        .format(new Date(item.lastAccess));
                tile.addView(text(recent, 10, MUTED, false));
            }
            tile.setOnClickListener(v -> showBookCarouselActions(item));
            thumbs.add(new CarouselThumbnail(item, thumbnail, fallback));
        }

        horizontal.setOnScrollChangeListener((v,x,y,oldX,oldY)->{
            allBooksScrollX = x;
            loadVisibleThumbnails(thumbs, x, horizontal.getWidth());
        });
        body.addView(horizontal, margins(-1, -2, 0, 0, 0, 6));
        horizontal.post(()->{
            horizontal.scrollTo(allBooksScrollX, 0);
            loadVisibleThumbnails(thumbs, allBooksScrollX, horizontal.getWidth());
        });
        TextView note = text("Книги из библиотеки BOOX. Обложки загружаются, если файл доступен.",
                10, MUTED, false);
        body.addView(note, margins(-1, -2, 0, 0, 0, 3));
    }

    private static final class CarouselThumbnail {
        final BooxLibrary.Book book;
        final ImageView image;
        final TextView fallback;
        CarouselThumbnail(BooxLibrary.Book book, ImageView image, TextView fallback) {
            this.book = book;
            this.image = image;
            this.fallback = fallback;
        }
    }

    private String bookLabel(String input) {
        if (input == null || input.trim().isEmpty()) return "КНИГА";
        String s = input.replace('\\','/');
        s = s.substring(s.lastIndexOf('/') + 1);
        s = s.replaceFirst("(?i)\\.(epub|fb2|fb2\\.zip|pdf|mobi|azw3|txt|djvu)$", "");
        return s.trim().isEmpty() ? "КНИГА" : s;
    }

    private void loadVisibleThumbnails(List<CarouselThumbnail> thumbs, int position, int viewportWidth) {
        int width = dp(139);
        int start = Math.max(0, position / width - 1);
        int last = Math.min(thumbs.size(), (position + Math.max(viewportWidth, dp(300))) / width + 3);
        for (int i = start; i < last; i++) {
            CarouselThumbnail entry = thumbs.get(i);
            if (entry.book.path == null || entry.book.path.trim().isEmpty()) continue;
            String cacheKey = entry.book.path;
            Bitmap cached = thumbnailCache.get(cacheKey);
            if (cached != null) {
                entry.image.setImageBitmap(cached);
                entry.image.setVisibility(View.VISIBLE);
                entry.fallback.setVisibility(View.GONE);
                continue;
            }
            if (!coverAttempted.add(cacheKey)) continue;
            thumbnailWorker.execute(() -> {
                Bitmap cover = null;
                try {
                    File file = new File(cacheKey);
                    if (file.isFile() && file.canRead()) {
                        cover = BookPreview.extract(getApplicationContext(),
                                SharedFileProvider.uriFor(file), file.getName());
                    }
                } catch (Exception ignored) { }
                if (cover != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(cover, 220, 310, true);
                    if (scaled != cover) cover.recycle();
                    Bitmap result = scaled;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        thumbnailCache.put(cacheKey, result);
                        entry.image.setImageBitmap(result);
                        entry.image.setVisibility(View.VISIBLE);
                        entry.fallback.setVisibility(View.GONE);
                    });
                }
            });
        }
    }

    private void showBookCarouselActions(BooxLibrary.Book book) {
        String details = book.name;
        if (book.hasUsableProgress()) {
            details += "\nПрочитано: " + book.percent() + "%";
        }
        if (book.lastAccess > 0) {
            details += "\nПоследнее открытие: "
                    + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                    .format(new Date(book.lastAccess));
        }
        String[] options = {"Открыть файл книги", "На карточку Руслана", "На карточку Анны", "На карточку Амелии"};
        new AlertDialog.Builder(this)
            .setTitle("Книга из библиотеки")
            .setMessage(details)
            .setItems(options, (dialog, which) -> {
                if (which == 0) openCarouselBook(book);
                else {
                    int slot = which - 1;
                    bindBookToCard(slot, book);
                }
            })
            .setNegativeButton("Закрыть", null)
            .show();
    }

    private void openCarouselBook(BooxLibrary.Book book) {
        if (book.path == null || book.path.trim().isEmpty()) {
            Toast.makeText(this, "BOOX не передал расположение этого файла",
                    Toast.LENGTH_LONG).show();
            return;
        }
        File path = new File(book.path);
        if (!path.isFile() || !path.canRead()) {
            Toast.makeText(this, "Доступ к файлу ограничен. Открой его в библиотеке BOOX.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        openDocument(SharedFileProvider.uriFor(path), FilesCatalog.mime(path));
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
        body.addView(new AppsDrawer(this), margins(-1, -2, 0, 0, 0, 4));
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

    private static final class SettingsItem {
        final String name, glyph;
        final Runnable action;
        SettingsItem(String name, String glyph, Runnable action) {
            this.name=name; this.glyph=glyph; this.action=action;
        }
    }

    private SettingsItem setting(String name, String glyph, Runnable action) {
        return new SettingsItem(name, glyph, action);
    }

    private void settingsGrid(LinearLayout parent, String group, SettingsItem... items) {
        TextView header=text(group, 11, MUTED, true);
        header.setLetterSpacing(.08f);
        parent.addView(header, margins(-1,-2,0,17,0,10));
        final int cols=4;
        for (int offset=0;offset<items.length;offset+=cols) {
            LinearLayout line=horizontal();
            for (int col=0;col<cols;col++) {
                int index=offset+col;
                if (index>=items.length) {
                    line.addView(new View(this),new LinearLayout.LayoutParams(0,dp(104),1));
                    continue;
                }
                SettingsItem item=items[index];
                LinearLayout tile=vertical();
                tile.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                tile.setPadding(dp(2),dp(7),dp(2),dp(5));
                SettingsGlyph glyph=new SettingsGlyph(this,item.glyph);
                LinearLayout.LayoutParams glyphParams=new LinearLayout.LayoutParams(dp(37),dp(37));
                glyphParams.gravity=Gravity.CENTER_HORIZONTAL;
                glyphParams.bottomMargin=dp(6);
                tile.addView(glyph,glyphParams);

                TextView name=text(item.name,11,INK,false);
                name.setGravity(Gravity.CENTER);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                tile.addView(name,new LinearLayout.LayoutParams(-1,-2));
                tile.setOnClickListener(v -> item.action.run());
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(104),1);
                line.addView(tile,p);
            }
            parent.addView(line,margins(-1,-2,0,0,0,3));
        }
    }

    private void openSystemPanel(String action, String what) {
        try {
            startActivity(new Intent(action));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
                Toast.makeText(this,
                    "Прямой вход в «"+what+"» недоступен. Открой раздел в системных настройках.",
                    Toast.LENGTH_LONG).show();
            } catch (Exception e2) {
                new AlertDialog.Builder(this)
                    .setTitle(what)
                    .setMessage("Прошивка BOOX не позволяет открыть этот раздел извне.")
                    .setPositiveButton("ОК",null).show();
            }
        }
    }

    private void nativeBooxSettings(String destination) {
        new AlertDialog.Builder(this)
            .setTitle(destination)
            .setMessage("Это фирменный раздел BOOX. На Darwin 9 стороннее приложение не всегда может открыть его напрямую. После перехода найди пункт «"+destination+"» в родных настройках.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Открыть настройки", (d,w) ->
                    openSystemPanel(Settings.ACTION_SETTINGS, destination))
            .show();
    }

    private void openUnknownSources() {
        try {
            Intent i=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            i.setData(Uri.parse("package:"+getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            openSystemPanel(Settings.ACTION_SECURITY_SETTINGS,"Установка APK");
        }
    }

    private void showDeviceStorage() {
        LinearLayout[] output=new LinearLayout[1];
        ScrollView scroll=newSection(output);
        LinearLayout body=output[0];
        sectionHeading(body,"Память устройства","Хранилище и доступные файлы");

        try {
            StatFs stat=new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
            long total=stat.getTotalBytes();
            long free=stat.getAvailableBytes();
            long used=Math.max(0,total-free);
            LinearLayout panel=vertical();
            panel.setPadding(dp(15),dp(16),dp(15),dp(16));
            panel.setBackground(panel(WASH,EDGE,9));
            panel.addView(text("Внутреннее хранилище",16,INK,true),
                    margins(-1,-2,0,0,0,7));
            panel.addView(text("Использовано: "+Formatter.formatShortFileSize(this,used),
                    15,INK,false),margins(-1,-2,0,0,0,4));
            panel.addView(text("Доступно: "+Formatter.formatShortFileSize(this,free)
                    +" из "+Formatter.formatShortFileSize(this,total),
                    12,MUTED,false),margins(-1,-2,0,0,0,11));
            int percent=total<=0?0:(int)Math.max(0,Math.min(100,used*100L/total));
            LinearLayout bar=horizontal();
            bar.setBackgroundColor(0xffdddddd);
            View filled=new View(this);filled.setBackgroundColor(INK);
            bar.addView(filled,new LinearLayout.LayoutParams(0,dp(9),percent));
            bar.addView(new View(this),new LinearLayout.LayoutParams(0,dp(9),100-percent));
            panel.addView(bar,margins(-1,9,0,0,0,0));
            body.addView(panel,margins(-1,-2,0,0,0,14));
        } catch (Exception e) {
            body.addView(text("Не удалось прочитать объём хранилища",13,MUTED,false));
        }
        sectionButton(body,"Открыть файлы",()->showTab(1));
        sectionButton(body,"Системные настройки памяти",()->
                openSystemPanel(Settings.ACTION_INTERNAL_STORAGE_SETTINGS,"Хранилище"));
        sectionButton(body,"Разрешения на доступ к файлам",this::askFilesAccess);
        body.addView(text("Показатели относятся к доступному внутреннему разделу памяти, а не к облачному хранилищу.",12,MUTED,false));
        sectionButton(body,"← Все настройки",this::renderSettingsTab);
        showPage(scroll,3);
    }

    private void renderSettingsTab() {
        readingSettingsOpen = false;
        LinearLayout[] output=new LinearLayout[1];
        ScrollView scroll=newSection(output);
        LinearLayout body=output[0];

        body.addView(text("НАСТРОЙКИ УСТРОЙСТВА",10,MUTED,true),
                margins(-1,-2,0,0,0,4));
        body.addView(text("Настройки",25,INK,true),
                margins(-1,-2,0,0,0,12));

        LinearLayout device=vertical();
        device.setPadding(dp(13),dp(12),dp(13),dp(13));
        device.setBackground(panel(WASH,EDGE,8));
        String model=Build.MODEL==null?"ONYX BOOX":Build.MODEL;
        device.addView(text(model.toUpperCase(Locale.ROOT),15,INK,true),
                margins(-1,-2,0,0,0,3));
        device.addView(text("Android "+Build.VERSION.RELEASE,11,MUTED,false),
                margins(-1,-2,0,0,0,6));

        String memory="";
        try {
            StatFs stat=new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
            memory=" · Свободно "+Formatter.formatShortFileSize(this,stat.getAvailableBytes());
        } catch(Exception ignored) {}
        int charge=-1;
        try {
            Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery!=null) {
                int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
                int scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
                if(level>=0 && scale>0) charge=(int)Math.round(100.0*level/scale);
            }
        } catch(Exception ignored) {}
        String status=(charge>=0?"Заряд "+charge+"%":"Питание")+memory;
        device.addView(text(status,12,INK,false));
        device.setOnClickListener(v->openSystemPanel(Settings.ACTION_DEVICE_INFO_SETTINGS,"Об устройстве"));
        body.addView(device,margins(-1,-2,0,0,0,8));

        settingsGrid(body,"ПОДКЛЮЧЕНИЕ",
            setting("Wi-Fi","wifi",()->openSystemPanel(Settings.ACTION_WIFI_SETTINGS,"Wi-Fi")),
            setting("Bluetooth","bluetooth",()->openSystemPanel(Settings.ACTION_BLUETOOTH_SETTINGS,"Bluetooth")),
            setting("Сети","globe",()->openSystemPanel(Settings.ACTION_WIRELESS_SETTINGS,"Сети")),
            setting("Аккаунты","account",()->openSystemPanel("android.settings.SYNC_SETTINGS","Учетные записи"))
        );

        settingsGrid(body,"УСТРОЙСТВО",
            setting("Память","storage",this::showDeviceStorage),
            setting("Приложения","apps",()->openSystemPanel(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,"Приложения")),
            setting("Экран","screen",()->openSystemPanel(Settings.ACTION_DISPLAY_SETTINGS,"Экран")),
            setting("Питание","battery",()->openSystemPanel("android.settings.BATTERY_SAVER_SETTINGS","Питание")),
            setting("Дата и время","clock",()->openSystemPanel(Settings.ACTION_DATE_SETTINGS,"Дата и время")),
            setting("Язык","globe",()->openSystemPanel(Settings.ACTION_LOCALE_SETTINGS,"Язык")),
            setting("Звук","notifications",()->openSystemPanel(Settings.ACTION_SOUND_SETTINGS,"Звук")),
            setting("Клавиатура","keyboard",()->openSystemPanel(Settings.ACTION_INPUT_METHOD_SETTINGS,"Клавиатура"))
        );

        settingsGrid(body,"СИСТЕМА",
            setting("Обновления","update",()->openSystemPanel("android.settings.SYSTEM_UPDATE_SETTINGS","Обновления системы BOOX")),
            setting("Интерфейс BOOX","settings",()->nativeBooxSettings("Настройки интерфейса")),
            setting("Кнопки BOOX","buttons",()->nativeBooxSettings("Настройка кнопок")),
            setting("Пароль","lock",()->openSystemPanel(Settings.ACTION_SECURITY_SETTINGS,"Пароль и безопасность")),
            setting("Уведомления","notifications",()->openSystemPanel("android.settings.NOTIFICATION_SETTINGS","Уведомления")),
            setting("Спец. возможности","access",()->openSystemPanel(Settings.ACTION_ACCESSIBILITY_SETTINGS,"Специальные возможности")),
            setting("Безопасность","shield",()->openSystemPanel(Settings.ACTION_SECURITY_SETTINGS,"Безопасность")),
            setting("О системе","about",()->openSystemPanel(Settings.ACTION_DEVICE_INFO_SETTINGS,"Об устройстве"))
        );

        settingsGrid(body,"НАША ОБОЛОЧКА",
            setting("Книги семьи","book",this::renderReadingSettings),
            setting("Библиотека","book",()->refreshBooxLibrary(true)),
            setting("Все файлы","folder",()->showTab(1)),
            setting("Все приложения","apps",()->showTab(2)),
            setting("Главный экран","home",this::openHomeSettings),
            setting("Доступ к файлам","files",this::askFilesAccess),
            setting("Установка APK","key",this::openUnknownSources),
            setting("Приложение Duo","about",()->appDetails(getPackageName()))
        );
        settingsGrid(body,"ДОПОЛНИТЕЛЬНО",
            setting("Все настройки","settings",()->openSystemPanel(Settings.ACTION_SETTINGS,"Системные настройки")),
            setting("Настройки BOOX","buttons",()->nativeBooxSettings("Настройки устройства")),
            setting("Системная память","storage",()->
                    openSystemPanel(Settings.ACTION_INTERNAL_STORAGE_SETTINGS,"Память устройства")),
            setting("Обновить книги","update",()->refreshBooxLibrary(true))
        );
        body.addView(text("BOOX DUO 0.14 · Только ссылки на системные разделы, без изменения прошивки.",
                10,MUTED,false),margins(-1,-2,0,11,0,9));
        showPage(scroll,3);
    }

    private void renderReadingSettings() {
        readingSettingsOpen = true;
        LinearLayout[] output = new LinearLayout[1];
        ScrollView scroll = newSection(output);
        LinearLayout body = output[0];
        sectionHeading(body, "Книги семьи", "Параметры читалки и личные книжные полки");
        sectionButton(body, "← Все настройки", this::renderSettingsTab);
        body.addView(text("СИСТЕМНАЯ БИБЛИОТЕКА BOOX", 13, INK, true),
                margins(-1, -2, 0, 0, 0, 5));
        String booxStatus = booxLoading ? "Проверяю доступ…" : booxLibrary == null
                ? "Ещё не проверено" : booxLibrary.details;
        body.addView(text(booxStatus, 12, MUTED, false),
                margins(-1, -2, 0, 0, 0, 7));
        sectionButton(body, "Проверить библиотеку BOOX (только чтение)", () -> refreshBooxLibrary(true));
        body.addView(text("Если библиотека доступна, привяжи книгу Анны к её записи: тогда прогресс и последнее открытие будут обновляться при возвращении в оболочку.", 12, MUTED, false),
                margins(-1, -2, 0, 0, 0, 8));
        AppEntry shared = familyReader();
        body.addView(text("ОБЩАЯ ЧИТАЛКА", 13, INK, true),
                margins(-1, -2, 0, 9, 0, 5));
        body.addView(text(shared == null ? "Не выбрана" : shared.label, 12, MUTED, false),
                margins(-1, -2, 0, 0, 0, 6));
        sectionButton(body, "Выбрать приложение для всей семьи", this::showFamilyReaderPicker);
        body.addView(text("В каждом слоте — свой файл книги. Настройки AlReaderX общие, позиции чтения зависят от самой читалки.",
                12, MUTED, false), margins(-1, -2, 0, 0, 0, 6));
        for (int slot = 0; slot < 3; slot++) addSettingsForSlot(body, slot);
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
        body.addView(text("BOOX DUO 0.14 · Android 11 · Офлайн", 11, MUTED, false),
                margins(-1, -2, 0, 8, 0, 5));
        showPage(scroll, 3);
    }

    @Override public void onBackPressed() {
        if (activeTab == 3 && readingSettingsOpen) {
            renderSettingsTab();
            return;
        }
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

    private View readerCard(int slot,String owner,String unusedTitle,String unusedMonogram) {
        LinearLayout card=horizontal();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(6),dp(6),dp(6),dp(6));
        card.setBackground(panel(PAPER,EDGE,8));
        card.setOnClickListener(v->{
            if(linkedBooxBook(slot)==null && savedBook(slot)==null)showBooxBookPicker(slot);
            else openReader(slot);
        });
        card.setOnLongClickListener(v->{showBooxBookPicker(slot);return true;});

        // Three square image assets supplied by the family. The lettering is
        // already in each JPEG: never draw a second title over the cover.
        ImageView art=new ImageView(this);
        art.setImageResource(slot==0?R.drawable.shelf_ruslan:
                slot==1?R.drawable.shelf_anna:R.drawable.shelf_amelia);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams coverParams=new LinearLayout.LayoutParams(dp(124),dp(124));
        coverParams.rightMargin=dp(9);
        card.addView(art,coverParams);

        FrameLayout infoFrame=new FrameLayout(this);
        card.addView(infoFrame,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout info=vertical();
        info.setPadding(0,0,0,dp(29));
        infoFrame.addView(info,new FrameLayout.LayoutParams(-1,-1));

        BooxLibrary.Book book=linkedBooxBook(slot);
        String titleValue=book!=null && !book.name.isEmpty()
                ? book.name:prefs.getString("title_"+slot,"");
        if(TextUtils.isEmpty(titleValue) || "Моя книга".equals(titleValue)
                || "Книга Анны".equals(titleValue) || "Книга Амелии".equals(titleValue)) {
            titleValue=prefs.getString("book_name_"+slot,"Книга не выбрана");
        }
        TextView tag=text("СЕЙЧАС ЧИТАЕТ",9,MUTED,true);
        tag.setLetterSpacing(.04f);
        info.addView(tag,margins(-1,-2,0,0,0,2));

        TextView name=text(titleValue,15,INK,true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name,margins(-1,-2,0,0,0,2));

        AppEntry reader=familyReader();
        TextView app=text(reader==null?"Выбери читалку":reader.label,10,MUTED,false);
        app.setMaxLines(1);
        app.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(app,margins(-1,-2,0,0,0,3));

        boolean trusted=book!=null && book.hasUsableProgress();
        int current=trusted?book.currentPage:prefs.getInt("page_"+slot,0);
        int total=trusted?book.totalPages:prefs.getInt("total_"+slot,0);
        boolean manual=!trusted && total>=3 && current>=0 && current<=total;
        boolean known=trusted||manual;
        if(known){
            int percent=Math.min(100,Math.round(100f*current/total));
            String label=trusted && book.normalizedProgress()?percent+"%":
                    current+"/"+total+" · "+percent+"%";
            info.addView(text(label+" · "+(trusted?"BOOX":"вручную"),10,MUTED,false),
                    margins(-1,-2,0,0,0,3));
            LinearLayout bar=horizontal();
            bar.setBackgroundColor(WASH);
            View filled=new View(this);
            filled.setBackgroundColor(INK);
            bar.addView(filled,new LinearLayout.LayoutParams(0,dp(3),percent));
            bar.addView(new View(this),new LinearLayout.LayoutParams(0,dp(3),100-percent));
            info.addView(bar,margins(-1,3,0,0,0,1));
        }else{
            info.addView(text("Прогресс не определён",10,MUTED,false),
                    margins(-1,-2,0,0,0,3));
        }

        View divider=separator();
        FrameLayout.LayoutParams dividerPos=new FrameLayout.LayoutParams(-1,dp(1),Gravity.BOTTOM);
        dividerPos.bottomMargin=dp(31);
        infoFrame.addView(divider,dividerPos);

        boolean selected=book!=null || savedBook(slot)!=null;
        TextView change=text(selected?"СМЕНИТЬ":"ВЫБРАТЬ КНИГУ",10,INK,true);
        change.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        change.setPadding(dp(7),0,dp(2),0);
        change.setOnClickListener(v->showBooxBookPicker(slot));
        infoFrame.addView(change,new FrameLayout.LayoutParams(-2,dp(30),Gravity.RIGHT|Gravity.BOTTOM));
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
        content.addView(text("Три отдельные книги открываются в одной читалке. Изменить приложение можно в разделе настроек.", 13, MUTED, false),
                margins(-1, -2, 0, 0, 0, 12));
        for (int slot = 0; slot < 3; slot++) addSettingsForSlot(content, slot);
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
        String name = slot == 0 ? "Руслан" : slot == 1 ? "Анна" : "Амелия";
        parent.addView(text(name, 17, INK, true), margins(-1, -2, 0, 13, 0, 5));
        AppEntry current = familyReader();
        TextView currentApp = text("Общая читалка: " + (current == null ? "не выбрана" : current.label), 12, MUTED, false);
        parent.addView(currentApp, margins(-1, -2, 0, 0, 0, 5));
        EditText editTitle = new EditText(this);
        editTitle.setTextSize(14);
        editTitle.setTextColor(INK);
        editTitle.setSingleLine(true);
        editTitle.setHint("Название книги (необязательно)");
        editTitle.setText(prefs.getString("title_" + slot,
                slot == 0 ? "Моя книга" : slot == 1 ? "Книга Анны" : "Книга Амелии"));
        editTitle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                prefs.edit().putString("title_" + slot, s.toString()).apply();
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        parent.addView(editTitle, margins(-1, 44, 0, 0, 0, 6));
        parent.addView(simpleAction("Выбрать книгу из библиотеки BOOX",
                () -> showBooxBookPicker(slot)), margins(-1, 43, 0, 0, 0, 5));
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
        BooxLibrary.Book linked = linkedBooxBook(slot);
        boolean sameBook = linked != null && (bookStem(filename).equals(bookStem(linked.name))
                || bookStem(filename).equals(bookStem(linked.path)));
        String key = sameBook ? linked.key() : "file:" + uri.toString();
        SharedPreferences.Editor edit = prefs.edit()
                .putString("book_" + slot, uri.toString())
                .putString("book_name_" + slot, filename)
                .putString("bound_book_key_" + slot, key)
                .putString("title_" + slot, sameBook && !linked.name.isEmpty()
                        ? linked.name : filename)
                .putBoolean("direct_" + slot, true);
        if (!sameBook) edit.remove("boox_link_" + slot);
        if (!sameBook) edit.remove("page_" + slot).remove("total_" + slot);
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
        if (requestCode >= PICK_BOOK_BASE && requestCode <= PICK_BOOK_BASE + 2) {
            onBookSelected(requestCode - PICK_BOOK_BASE, data);
            return;
        }
        if (requestCode < PICK_COVER_BASE || requestCode > PICK_COVER_BASE + 2) return;
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
