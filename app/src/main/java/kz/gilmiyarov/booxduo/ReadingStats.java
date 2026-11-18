package kz.gilmiyarov.booxduo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local, offline reading journal, scoped to the user's three family slots.
 * No usage-access permissions and no invented stats from inaccessible AlReaderX data.
 */
final class ReadingStats extends SQLiteOpenHelper {
    private static final int VERSION = 1;
    static final int WORDS_PER_PAGE_ESTIMATE = 250;

    ReadingStats(Context context) {
        super(context, "boox_duo_reading.db", null, VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "slot INTEGER NOT NULL, book_key TEXT NOT NULL, title TEXT NOT NULL," +
                "started INTEGER NOT NULL, ended INTEGER NOT NULL, day TEXT NOT NULL," +
                "minutes INTEGER NOT NULL, pages INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_session_slot_day ON sessions(slot,day)");
        db.execSQL("CREATE TABLE finished (" +
                "slot INTEGER NOT NULL, book_key TEXT NOT NULL, title TEXT NOT NULL," +
                "finished_at INTEGER NOT NULL, PRIMARY KEY (slot,book_key))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    private static String dateFor(long epochMillis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                .format(new Date(epochMillis));
    }

    static class Summary {
        int minutes, pages, started, completed;
        long wordsEstimate() { return (long)pages * WORDS_PER_PAGE_ESTIMATE; }
    }
    static class Session {
        long id, started, ended;
        int minutes, pages;
        String title, bookKey, day;
    }
    static class Finished {
        String bookKey, title;
        long at;
    }

    void addSession(int slot, String key, String title, long start, long end,
                    int minutes, int pages) {
        if (slot < 0 || slot > 2 || minutes <= 0) return;
        ContentValues v = new ContentValues();
        v.put("slot", slot);
        v.put("book_key", safeKey(key,title));
        v.put("title", safeTitle(title));
        v.put("started", Math.max(0L,start));
        v.put("ended", Math.max(0L,end));
        v.put("day", dateFor(end));
        v.put("minutes", Math.max(0,Math.min(minutes,720)));
        v.put("pages", Math.max(0,Math.min(pages,20000)));
        getWritableDatabase().insert("sessions", null, v);
    }
    static String safeKey(String key,String title) {
        return key == null || key.trim().isEmpty() ? "title:" + safeTitle(title) : key;
    }
    static String safeTitle(String value) {
        return value == null || value.trim().isEmpty() ? "Книга без названия" : value.trim();
    }

    Summary summary(int slot) {
        Summary s = new Summary();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c=db.rawQuery(
                "SELECT COALESCE(SUM(minutes),0),COALESCE(SUM(pages),0),COUNT(DISTINCT book_key) " +
                "FROM sessions WHERE slot=?",new String[]{String.valueOf(slot)})) {
            if(c.moveToFirst()){
                s.minutes=c.getInt(0);
                s.pages=c.getInt(1);
                s.started=c.getInt(2);
            }
        }
        try (Cursor c=db.rawQuery("SELECT COUNT(*) FROM finished WHERE slot=?",
                new String[]{String.valueOf(slot)})) {
            if(c.moveToFirst())s.completed=c.getInt(0);
        }
        return s;
    }

    Map<Integer,Integer> monthDays(int slot, int year, int month) {
        Map<Integer,Integer> map = new HashMap<>();
        String prefix = String.format(Locale.ROOT,"%04d-%02d-",year,month);
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT day,SUM(minutes) FROM sessions WHERE slot=? AND day LIKE ? GROUP BY day",
                new String[]{String.valueOf(slot),prefix+"%"})) {
            while(c.moveToNext()){
                String key=c.getString(0);
                if(key!=null && key.length()>=10){
                    try { map.put(Integer.parseInt(key.substring(8,10)),c.getInt(1)); }
                    catch(NumberFormatException ignored){}
                }
            }
        }
        return map;
    }

    List<Session> recent(int slot,int limit) {
        ArrayList<Session> list=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT id,book_key,title,started,ended,day,minutes,pages " +
                "FROM sessions WHERE slot=? ORDER BY ended DESC LIMIT ?",
                new String[]{String.valueOf(slot),String.valueOf(Math.max(1,limit))})) {
            while(c.moveToNext()){
                Session item=new Session();
                item.id=c.getLong(0);item.bookKey=c.getString(1);item.title=c.getString(2);
                item.started=c.getLong(3);item.ended=c.getLong(4);item.day=c.getString(5);
                item.minutes=c.getInt(6);item.pages=c.getInt(7);
                list.add(item);
            }
        }
        return list;
    }

    List<Finished> finished(int slot) {
        ArrayList<Finished> list=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT book_key,title,finished_at FROM finished " +
                "WHERE slot=? ORDER BY finished_at DESC",
                new String[]{String.valueOf(slot)})) {
            while(c.moveToNext()){
                Finished f=new Finished();f.bookKey=c.getString(0);
                f.title=c.getString(1);f.at=c.getLong(2);
                list.add(f);
            }
        }
        return list;
    }

    void markFinished(int slot,String key,String title) {
        ContentValues v=new ContentValues();
        v.put("slot",slot);v.put("book_key",safeKey(key,title));
        v.put("title",safeTitle(title));v.put("finished_at",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("finished",null,v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }
    void unmarkFinished(int slot,String key){
        getWritableDatabase().delete("finished","slot=? AND book_key=?",
                new String[]{String.valueOf(slot),key});
    }
    void deleteSession(long id) {
        getWritableDatabase().delete("sessions","id=?",new String[]{String.valueOf(id)});
    }
}
