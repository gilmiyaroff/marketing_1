package kz.gilmiyarov.booxduo;

import android.os.Environment;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** A read-only catalog of shared device storage for user-granted Android 11 file access. */
final class FilesCatalog {
    static final int DOCUMENTS = 0;
    static final int IMAGES = 1;
    static final int AUDIO = 2;
    static final int VIDEO = 3;
    static final int DOWNLOADS = 4;
    static final int BLUETOOTH = 5;
    static final int APK = 6;
    static final int FONTS = 7;
    static final String[] LABELS = {
            "Документы", "Изображения", "Аудио", "Видео",
            "Загрузки", "Bluetooth", "APK", "Шрифты"
    };
    static final String[] SIGNS = {
            "DOC", "IMG", "AUD", "VID", "DL", "BT", "APK", "Aa"
    };
    static class Snapshot {
        final ArrayList<File>[] groups;
        final boolean truncated;
        @SuppressWarnings("unchecked")
        Snapshot(boolean truncated) {
            this.truncated = truncated;
            this.groups = (ArrayList<File>[]) new ArrayList[8];
            for (int i=0; i<groups.length; i++) groups[i] = new ArrayList<>();
        }
        int count(int i) { return groups[i].size(); }
    }

    static File root() { return Environment.getExternalStorageDirectory(); }
    static boolean canRead() { return Environment.isExternalStorageManager(); }

    private static boolean extension(String n, String... ext) {
        for (String suffix : ext) if (n.endsWith("." + suffix)) return true;
        return false;
    }
    private static boolean isDocument(String n) {
        return extension(n, "epub","fb2","fb3","mobi","azw","azw3","pdf","txt",
                "doc","docx","odt","rtf","html","htm","cbz","cbr","djvu","djv","xlsx","csv");
    }
    private static boolean isImage(String n) {
        return extension(n, "jpg","jpeg","png","gif","bmp","webp","tiff","heic","svg");
    }
    private static boolean isAudio(String n) {
        return extension(n, "mp3","ogg","opus","m4a","flac","wav","aac","wma");
    }
    private static boolean isVideo(String n) {
        return extension(n, "mp4","mkv","avi","mov","webm","3gp","m4v");
    }
    static boolean isIn(File f, String folder) {
        String p = f.getAbsolutePath().replace('\\', '/').toLowerCase(Locale.ROOT);
        return p.contains("/" + folder.toLowerCase(Locale.ROOT) + "/");
    }
    static Snapshot scan() {
        File root = root();
        Snapshot result = new Snapshot(false);
        ArrayDeque<File> directories = new ArrayDeque<>();
        directories.add(root);
        int visited = 0;
        final int maximum = 25000;
        while (!directories.isEmpty() && visited < maximum) {
            File current = directories.removeFirst();
            File[] children = current.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (++visited > maximum) return result;
                String name = child.getName();
                if (name.startsWith(".") || name.equalsIgnoreCase("Android")) continue;
                if (child.isDirectory()) {
                    directories.add(child);
                    continue;
                }
                if (!child.isFile()) continue;
                String n = name.toLowerCase(Locale.ROOT);
                if (isDocument(n)) result.groups[DOCUMENTS].add(child);
                if (isImage(n)) result.groups[IMAGES].add(child);
                if (isAudio(n)) result.groups[AUDIO].add(child);
                if (isVideo(n)) result.groups[VIDEO].add(child);
                if (isIn(child, "Download") || isIn(child, "Downloads")) result.groups[DOWNLOADS].add(child);
                if (isIn(child, "bluetooth")) result.groups[BLUETOOTH].add(child);
                if (extension(n, "apk")) result.groups[APK].add(child);
                if (extension(n, "ttf","otf","woff")) result.groups[FONTS].add(child);
            }
        }
        Comparator<File> byDate = (a,b) -> Long.compare(b.lastModified(), a.lastModified());
        for (ArrayList<File> group : result.groups) Collections.sort(group, byDate);
        return result;
    }
    static List<File> directory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return Collections.emptyList();
        ArrayList<File> list = new ArrayList<>(Arrays.asList(files));
        list.removeIf(f -> f.getName().startsWith(".") || f.getName().equals("Android"));
        Collections.sort(list, (a,b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return list;
    }
    static String mime(File file) {
        String n = file.getName().toLowerCase(Locale.ROOT);
        if (extension(n, "epub")) return "application/epub+zip";
        if (extension(n, "fb2")) return "application/x-fictionbook+xml";
        if (extension(n, "fb3")) return "application/octet-stream";
        if (extension(n, "pdf")) return "application/pdf";
        if (extension(n, "apk")) return "application/vnd.android.package-archive";
        if (extension(n, "txt")) return "text/plain";
        if (extension(n, "jpg","jpeg")) return "image/jpeg";
        if (extension(n, "png")) return "image/png";
        if (extension(n, "webp")) return "image/webp";
        if (extension(n, "mp3")) return "audio/mpeg";
        if (extension(n, "mp4")) return "video/mp4";
        android.webkit.MimeTypeMap map = android.webkit.MimeTypeMap.getSingleton();
        int dot=n.lastIndexOf('.');
        String mime = dot>=0 ? map.getMimeTypeFromExtension(n.substring(dot+1)) : null;
        return mime != null ? mime : "application/octet-stream";
    }
}
