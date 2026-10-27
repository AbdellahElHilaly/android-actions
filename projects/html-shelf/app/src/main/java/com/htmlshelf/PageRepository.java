package com.htmlshelf;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Only metadata is stored in SQLite; saved HTML assets live in private app files. */
public final class PageRepository extends SQLiteOpenHelper {
    public static final class Page {
        public final long id;
        public final String title;
        public final String folder;
        public final String root;
        public final int position;

        Page(long id, String title, String folder, String root, int position) {
            this.id = id;
            this.title = title;
            this.folder = folder;
            this.root = root;
            this.position = position;
        }
    }

    public PageRepository(Context context) {
        super(context, "pages.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL, folder TEXT NOT NULL UNIQUE," +
                "site_root TEXT NOT NULL, position INTEGER NOT NULL," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX pages_position ON pages(position, id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future migrations will be additive. No destructive reset.
    }

    private static Page read(Cursor c) {
        return new Page(c.getLong(0), c.getString(1), c.getString(2),
                c.getString(3), c.getInt(4));
    }

    public List<Page> list() {
        List<Page> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,title,folder,site_root,position FROM pages ORDER BY position,id", null)) {
            while (c.moveToNext()) result.add(read(c));
        }
        return result;
    }

    public Page find(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,title,folder,site_root,position FROM pages WHERE id=?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    public long add(String title, String folder, String siteRoot) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long next = 0;
            try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM pages", null)) {
                if (c.moveToFirst()) next = c.getLong(0);
            }
            ContentValues cv = new ContentValues();
            cv.put("title", title);
            cv.put("folder", folder);
            cv.put("site_root", siteRoot);
            cv.put("position", next);
            cv.put("created_at", System.currentTimeMillis());
            long id = db.insertOrThrow("pages", null, cv);
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }

    public void rename(long id, String title) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        getWritableDatabase().update("pages", values, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        getWritableDatabase().delete("pages", "id=?", new String[]{String.valueOf(id)});
    }

    public void move(long sourceId, long targetId, boolean after) {
        if (sourceId == targetId) return;
        List<Page> pages = list();
        Page source = null;
        for (Page p : pages) if (p.id == sourceId) { source = p; break; }
        if (source == null) return;
        pages.remove(source);
        int targetPosition = -1;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).id == targetId) { targetPosition = i; break; }
        }
        if (targetPosition < 0) return;
        pages.add(targetPosition + (after ? 1 : 0), source);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < pages.size(); i++) {
                ContentValues cv = new ContentValues();
                cv.put("position", i);
                db.update("pages", cv, "id=?", new String[]{String.valueOf(pages.get(i).id)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
