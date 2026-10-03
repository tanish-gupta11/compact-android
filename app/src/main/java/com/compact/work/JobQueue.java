package com.compact.work;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.net.Uri;
import com.compact.media.MediaItem;
import java.util.*;

public final class JobQueue extends SQLiteOpenHelper {
  public static final class Job {
    public long id, outputSize, created;
    public MediaItem item;
    public JobState state;
    public int mode;
    public boolean heic, trash, charging;
    public String outputUri, outputHash, sourceHash, reason, mime, publishName;
    public double score, min;
  }

  public JobQueue(Context c) {
    super(c, "compact.db", null, 1);
    setWriteAheadLoggingEnabled(true);
  }

  public void onCreate(SQLiteDatabase d) {
    d.execSQL(
        "CREATE TABLE jobs(id INTEGER PRIMARY KEY AUTOINCREMENT, uri TEXT NOT NULL,name TEXT,path"
            + " TEXT,mime TEXT,size INTEGER,taken INTEGER,modified INTEGER,width INTEGER,height"
            + " INTEGER,duration INTEGER,orientation INTEGER,video INTEGER,mode INTEGER,heic"
            + " INTEGER,trash INTEGER,charging INTEGER,state TEXT NOT NULL,output_uri"
            + " TEXT,output_hash TEXT,source_hash TEXT,output_size INTEGER DEFAULT 0,score REAL"
            + " DEFAULT 0,min_score REAL DEFAULT 0,reason TEXT DEFAULT '',output_mime"
            + " TEXT,publish_name TEXT,created INTEGER)");
  }

  public void onUpgrade(SQLiteDatabase d, int old, int next) {
    throw new IllegalStateException("Unsupported database migration");
  }

  public void enqueue(
      List<MediaItem> items, int mode, boolean heic, boolean trash, boolean charging) {
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      for (MediaItem m : items) {
        try (Cursor c =
            d.rawQuery(
                "SELECT id FROM jobs WHERE uri=? AND state NOT IN ('FAILED','SKIPPED','RESTORED')",
                new String[] {m.uri.toString()})) {
          if (c.moveToFirst()) continue;
        }
        ContentValues v = new ContentValues();
        v.put("uri", m.uri.toString());
        v.put("name", m.name);
        v.put("path", m.path);
        v.put("mime", m.mime);
        v.put("size", m.size);
        v.put("taken", m.dateTaken);
        v.put("modified", m.dateModified);
        v.put("width", m.width);
        v.put("height", m.height);
        v.put("duration", m.duration);
        v.put("orientation", m.orientation);
        v.put("video", m.video ? 1 : 0);
        v.put("mode", mode);
        v.put("heic", heic ? 1 : 0);
        v.put("trash", trash ? 1 : 0);
        v.put("charging", charging ? 1 : 0);
        v.put("state", "PENDING");
        v.put("created", System.currentTimeMillis());
        d.insertOrThrow("jobs", null, v);
      }
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
  }

  public List<Job> list() {
    List<Job> out = new ArrayList<>();
    try (Cursor c = getReadableDatabase().query("jobs", null, null, null, null, null, "id DESC")) {
      while (c.moveToNext()) out.add(read(c));
    }
    return out;
  }

  public Job get(long id) {
    try (Cursor c =
        getReadableDatabase()
            .query("jobs", null, "id=?", new String[] {"" + id}, null, null, null)) {
      return c.moveToFirst() ? read(c) : null;
    }
  }

  public Job next() {
    for (Job j : list()) if (j.state == JobState.PENDING) return j;
    return null;
  }

  public synchronized void update(long id, ContentValues values) {
    if (values.containsKey("state")) {
      Job old = get(id);
      JobState next = JobState.valueOf(values.getAsString("state"));
      if (old == null || !old.state.allows(next))
        throw new IllegalStateException("Invalid job transition");
    }
    getWritableDatabase().update("jobs", values, "id=?", new String[] {"" + id});
  }

  public void state(long id, JobState state, String reason) {
    ContentValues v = new ContentValues();
    v.put("state", state.name());
    v.put("reason", reason);
    update(id, v);
  }

  public void recover() {
    for (Job j : list())
      if (j.state.recovered() != j.state)
        state(j.id, j.state.recovered(), "Resumed after interruption");
  }

  private static String s(Cursor c, String k) {
    return c.getString(c.getColumnIndexOrThrow(k));
  }

  private static long n(Cursor c, String k) {
    return c.getLong(c.getColumnIndexOrThrow(k));
  }

  private static Job read(Cursor c) {
    Job j = new Job();
    j.id = n(c, "id");
    j.state = JobState.valueOf(s(c, "state"));
    j.mode = (int) n(c, "mode");
    j.heic = n(c, "heic") != 0;
    j.trash = n(c, "trash") != 0;
    j.charging = n(c, "charging") != 0;
    j.outputUri = s(c, "output_uri");
    j.outputHash = s(c, "output_hash");
    j.sourceHash = s(c, "source_hash");
    j.outputSize = n(c, "output_size");
    j.score = c.getDouble(c.getColumnIndexOrThrow("score"));
    j.min = c.getDouble(c.getColumnIndexOrThrow("min_score"));
    j.reason = s(c, "reason");
    j.mime = s(c, "output_mime");
    j.publishName = s(c, "publish_name");
    j.created = n(c, "created");
    MediaItem m = new MediaItem();
    j.item = m;
    m.uri = Uri.parse(s(c, "uri"));
    m.name = s(c, "name");
    m.path = s(c, "path");
    m.mime = s(c, "mime");
    m.size = n(c, "size");
    m.dateTaken = n(c, "taken");
    m.dateModified = n(c, "modified");
    m.width = (int) n(c, "width");
    m.height = (int) n(c, "height");
    m.duration = n(c, "duration");
    m.orientation = (int) n(c, "orientation");
    m.video = n(c, "video") != 0;
    return j;
  }
}
