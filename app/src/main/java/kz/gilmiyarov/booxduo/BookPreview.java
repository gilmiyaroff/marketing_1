package kz.gilmiyarov.booxduo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads covers from a user-selected book. Never reads another reader's private data. */
final class BookPreview {
    private static final int MAX_IMAGE = 5 * 1024 * 1024;
    private static final int MAX_FB2 = 14 * 1024 * 1024;

    private BookPreview() { }

    static Bitmap extract(Context context, Uri uri, String filename) {
        String n = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            if (n.endsWith(".pdf")) return pdf(context, uri);
            if (n.endsWith(".epub") || n.endsWith(".zip")) return zip(context, uri);
            if (n.endsWith(".fb2")) {
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    return fb2(readBounded(in, MAX_FB2));
                }
            }
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) {
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    return image(readBounded(in, MAX_IMAGE));
                }
            }
        } catch (Exception ignored) {
            // No cover is better than blocking the launcher or corrupting a book.
        }
        return null;
    }

    private static Bitmap zip(Context ctx, Uri uri) throws Exception {
        Bitmap first = null;
        try (ZipInputStream zip = new ZipInputStream(ctx.getContentResolver().openInputStream(uri))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null && ++count < 6000) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".fb2")) {
                    try {
                        Bitmap cover = fb2(readBounded(zip, MAX_FB2));
                        if (cover != null) return cover;
                    } catch (Exception ignored) { }
                } else if (isImage(name)) {
                    try {
                        Bitmap candidate = image(readBounded(zip, MAX_IMAGE));
                        if (candidate != null) {
                            if (name.contains("cover") || name.contains("obloj") || name.contains("облож")) {
                                return candidate;
                            }
                            if (first == null) first = candidate;
                            else candidate.recycle();
                        }
                    } catch (Exception ignored) { }
                }
            }
        }
        return first;
    }

    private static boolean isImage(String n) {
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    private static Bitmap fb2(byte[] bytes) {
        if (bytes == null) return null;
        String xml = new String(bytes, StandardCharsets.UTF_8);
        Matcher cover = Pattern.compile("(?s)<(?:[\\w-]+:)?coverpage\\b[^>]*>.*?href\\s*=\\s*['\"]#([^'\"]+)['\"].*?</(?:[\\w-]+:)?coverpage>", Pattern.CASE_INSENSITIVE).matcher(xml);
        if (!cover.find()) return null;
        String id = Pattern.quote(cover.group(1));
        Matcher binary = Pattern.compile("(?s)<(?:[\\w-]+:)?binary\\b(?=[^>]*\\bid\\s*=\\s*['\"]" + id + "['\"])[^>]*>(.*?)</(?:[\\w-]+:)?binary>", Pattern.CASE_INSENSITIVE).matcher(xml);
        if (!binary.find()) return null;
        try {
            String raw = binary.group(1).replaceAll("\\s+", "");
            if (raw.length() > MAX_IMAGE * 2) return null;
            return image(Base64.decode(raw, Base64.DEFAULT));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Bitmap pdf(Context ctx, Uri uri) throws Exception {
        try (ParcelFileDescriptor fd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd == null) return null;
            try (PdfRenderer renderer = new PdfRenderer(fd)) {
                if (renderer.getPageCount() == 0) return null;
                try (PdfRenderer.Page page = renderer.openPage(0)) {
                    int w = 320;
                    int h = Math.max(120, Math.min(650, Math.round((float) page.getHeight() * w / page.getWidth())));
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    return bmp;
                }
            }
        }
    }

    private static Bitmap image(byte[] data) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options size = new BitmapFactory.Options();
        size.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, size);
        int sample = 1;
        while (size.outWidth / sample > 420 || size.outHeight / sample > 560) sample *= 2;
        BitmapFactory.Options dec = new BitmapFactory.Options();
        dec.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, dec);
    }

    private static byte[] readBounded(InputStream in, int limit) throws Exception {
        if (in == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > limit) throw new IllegalArgumentException("Book part too large");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
