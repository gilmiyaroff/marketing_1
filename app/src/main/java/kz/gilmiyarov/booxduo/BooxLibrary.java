package kz.gilmiyarov.booxduo;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/**
 * Read-only adapter for Onyx's OPTIONAL public library metadata provider.
 * Some models expose it; Darwin 9 support must be verified on-device.
 * No insert/update/delete, root, ADB or private data access is performed.
 */
final class BooxLibrary {
    static final String AUTHORITY = "com.onyx.content.database.ContentProvider";
    static final Uri METADATA = Uri.parse("content://" + AUTHORITY + "/Metadata");

    static final class Book {
        final String id;
        final String uuid;
        final String name;
        final String path;
        final String progress;
        final long lastAccess;
        final int currentPage;
        final int totalPages;

        Book(String id, String uuid, String name, String path, String progress,
             long lastAccess, int currentPage, int totalPages) {
            this.id = id;
            this.uuid = uuid;
            this.name = name;
            this.path = path;
            this.progress = progress;
            this.lastAccess = lastAccess;
            this.currentPage = currentPage;
            this.totalPages = totalPages;
        }
        String key() {
            if (!uuid.isEmpty()) return "uuid:" + uuid;
            if (!path.isEmpty()) return "path:" + path;
            if (!id.isEmpty()) return "id:" + id;
            return "name:" + name;
        }
        String description() {
            String filename = !name.isEmpty() ? name : path;
            String p = totalPages > 0 ? " · " + currentPage + "/" + totalPages
                    : progress.isEmpty() ? "" : " · " + progress;
            return filename + p;
        }
    }

    static final class Result {
        final ArrayList<Book> books;
        final String status; // ok / unavailable / denied / error
        final String details;
        Result(ArrayList<Book> books, String status, String details) {
            this.books = books;
            this.status = status;
            this.details = details;
        }
    }

    private static String read(Cursor c, String... names) {
        for (String name : names) {
            for (int i=0; i<c.getColumnCount(); i++) {
                if (name.equalsIgnoreCase(c.getColumnName(i))) {
                    try {
                        if (!c.isNull(i)) {
                            String value = c.getString(i);
                            return value == null ? "" : value;
                        }
                    } catch (Exception ignored) { }
                }
            }
        }
        return "";
    }

    private static long timestamp(String value) {
        try {
            String raw = value.trim();
            if (raw.isEmpty()) return 0;
            long number = Long.parseLong(raw);
            if (number > 100000000000000L) number /= 1000L; // microseconds
            else if (number < 10000000000L && number > 100000000L) number *= 1000L; // seconds
            if (number < 946684800000L || number > System.currentTimeMillis() + 86400000L) return 0;
            return number;
        } catch (Exception e) { return 0; }
    }

    private static int[] progress(String input) {
        if (input == null) return new int[]{0, 0};
        String p = input.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d+)\\s*/\\s*(\\d+)$").matcher(p);
        if (!m.matches()) return new int[]{0,0};
        try {
            int current = Integer.parseInt(m.group(1));
            int total = Integer.parseInt(m.group(2));
            if (current < 0 || total <= 0 || current > total) return new int[]{0,0};
            return new int[]{current,total};
        } catch (Exception e) { return new int[]{0,0}; }
    }

    static Result readAll(ContentResolver resolver) {
        ArrayList<Book> books = new ArrayList<>();
        try (Cursor c = resolver.query(METADATA, null, null, null, null)) {
            if (c == null) return new Result(books, "unavailable",
                    "Провайдер библиотеки не отвечает на этой прошивке");
            int lines = 0;
            while (c.moveToNext() && lines++ < 4000) {
                String id = read(c, "id", "_id");
                String uuid = read(c, "uuid", "idString");
                String path = read(c, "nativeAbsolutePath", "absolutePath", "path");
                String name = read(c, "title", "name", "displayName", "fileName");
                String prog = read(c, "progress");
                long last = timestamp(read(c, "lastAccess", "lastOpenTime", "lastReadTime"));
                if (name.trim().isEmpty() && !path.trim().isEmpty()) {
                    String file = path.replace('\\','/');
                    name = file.substring(file.lastIndexOf('/') + 1);
                }
                if (name.trim().isEmpty() && path.trim().isEmpty()) continue;
                int[] pages = progress(prog);
                books.add(new Book(id, uuid, name, path, prog, last, pages[0], pages[1]));
            }
            Collections.sort(books, (a,b) -> Long.compare(b.lastAccess, a.lastAccess));
            return new Result(books, "ok", "Найдено записей: " + books.size());
        } catch (SecurityException ex) {
            return new Result(books, "denied", "Android запретил доступ к системной библиотеке");
        } catch (Exception ex) {
            String message = ex.getClass().getSimpleName();
            return new Result(books, "error", "Не удалось прочитать библиотеку (" + message + ")");
        }
    }

    static Book match(Result result, String key) {
        if (result == null || key == null || key.isEmpty()) return null;
        for (Book b : result.books) if (key.equals(b.key())) return b;
        return null;
    }
}
