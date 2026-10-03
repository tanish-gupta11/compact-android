package com.compact.media;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import com.compact.util.Files;
import com.compact.work.*;
import java.io.*;
import java.util.*;

public final class Replacer {
  public static void publish(Context c, JobQueue db, JobQueue.Job job, File source)
      throws Exception {
    String ext =
        job.mime.equals("video/mp4") ? "mp4" : job.mime.equals("image/heic") ? "heic" : "jpg";
    String name =
        job.item.base()
            + " (compact) "
            + job.id
            + "-"
            + java.util.UUID.randomUUID().toString().substring(0, 8)
            + "."
            + ext;
    ContentValues journal = new ContentValues();
    journal.put("publish_name", name);
    journal.put("state", "PUBLISHING");
    db.update(job.id, journal);
    ContentValues v = new ContentValues();
    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
    v.put(MediaStore.MediaColumns.MIME_TYPE, job.mime);
    v.put(MediaStore.MediaColumns.RELATIVE_PATH, job.item.path);
    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
    v.put("datetaken", job.item.dateTaken);
    Uri collection =
        job.item.video
            ? MediaStore.Video.Media.getContentUri(MediaStore.getVolumeName(job.item.uri))
            : MediaStore.Images.Media.getContentUri(MediaStore.getVolumeName(job.item.uri));
    Uri uri = c.getContentResolver().insert(collection, v);
    if (uri == null) throw new IOException("Could not create Gallery copy");
    journal = new ContentValues();
    journal.put("output_uri", uri.toString());
    db.update(job.id, journal);
    try {
      try (ParcelFileDescriptor fd = c.getContentResolver().openFileDescriptor(uri, "w");
          InputStream in = new FileInputStream(source);
          FileOutputStream out = new FileOutputStream(fd.getFileDescriptor())) {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        out.getFD().sync();
      }
      if (!job.outputHash.equals(Files.hash(c.getContentResolver().openInputStream(uri))))
        throw new IOException("Published bytes did not verify");
      ContentValues done = new ContentValues();
      done.put("datetaken", job.item.dateTaken);
      done.put(MediaStore.MediaColumns.IS_PENDING, 0);
      c.getContentResolver().update(uri, done, null, null);
      preserveDate(c, uri, job.item.dateModified);
      try (Cursor cursor =
          c.getContentResolver()
              .query(uri, new String[] {"datetaken", "relative_path", "_size"}, null, null, null)) {
        if (cursor == null
            || !cursor.moveToFirst()
            || cursor.getLong(0) != job.item.dateTaken
            || !Objects.equals(cursor.getString(1), job.item.path)
            || cursor.getLong(2) != source.length())
          throw new IOException("Gallery metadata did not verify");
      }
      db.state(
          job.id,
          job.trash ? JobState.PUBLISHED : JobState.DONE,
          job.trash
              ? "Verified copy ready. Review before approving system Trash."
              : "Kept both originals and compressed copies");
    } catch (Exception failure) {
      c.getContentResolver().delete(uri, null, null);
      throw failure;
    }
  }

  private static void preserveDate(Context c, Uri uri, long seconds) throws Exception {
    if (seconds <= 0) return;
    try (Cursor cursor =
        c.getContentResolver()
            .query(uri, new String[] {MediaStore.MediaColumns.DATA}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        String p = cursor.getString(0);
        if (p != null && !new File(p).setLastModified(seconds * 1000))
          throw new IOException("Cannot preserve file modification date");
      }
    }
  }

  public static void recover(Context c, JobQueue db) throws Exception {
    db.recover();
    for (JobQueue.Job j : db.list())
      if (j.state == JobState.PUBLISHING) {
        Uri uri = j.outputUri == null ? findPending(c, j) : Uri.parse(j.outputUri);
        if (uri != null) {
          boolean pending = false, exists = false;
          try (Cursor q =
              c.getContentResolver().query(uri, new String[] {"is_pending"}, null, null, null)) {
            if (q != null && q.moveToFirst()) {
              pending = q.getInt(0) == 1;
              exists = true;
            }
          }
          if (exists
              && !pending
              && !j.outputHash.equals(Files.hash(c.getContentResolver().openInputStream(uri)))) {
            db.state(
                j.id,
                JobState.FAILED,
                "Interrupted publication copy changed; retained for manual review");
            continue;
          }
          if (exists) c.getContentResolver().delete(uri, null, null);
        }
        ContentValues v = new ContentValues();
        v.putNull("output_uri");
        v.put("state", "PENDING");
        db.update(j.id, v);
      }
  }

  private static Uri findPending(Context c, JobQueue.Job j) {
    if (j.publishName == null) return null;
    Uri collection =
        j.item.video
            ? MediaStore.Video.Media.getContentUri(MediaStore.getVolumeName(j.item.uri))
            : MediaStore.Images.Media.getContentUri(MediaStore.getVolumeName(j.item.uri));
    Bundle args = new Bundle();
    args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
    args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "_display_name=? AND relative_path=?");
    args.putStringArray(
        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[] {j.publishName, j.item.path});
    try (Cursor cursor =
        c.getContentResolver()
            .query(collection, new String[] {"_id", "owner_package_name"}, args, null)) {
      if (cursor != null)
        while (cursor.moveToNext())
          if (c.getPackageName().equals(cursor.getString(1)))
            return ContentUris.withAppendedId(collection, cursor.getLong(0));
    }
    return null;
  }

  /** True when the item still exists, trashed or not. */
  public static boolean exists(Context c, Uri uri) {
    Bundle args = new Bundle();
    args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
    try (Cursor q = c.getContentResolver().query(uri, new String[] {"_id"}, args, null)) {
      return q != null && q.moveToFirst();
    }
  }

  /** Before permanently deleting a trashed original, the compressed copy must still be intact. */
  public static void ensurePurgeSafe(Context c, JobQueue.Job j) throws Exception {
    if (j.state != JobState.ORIGINAL_TRASHED || j.outputUri == null) throw new IOException("Not replaced");
    if (!j.outputHash.equals(
        Files.hash(c.getContentResolver().openInputStream(Uri.parse(j.outputUri)))))
      throw new IOException(j.item.name + ": compressed copy changed or is missing; original kept");
  }

  public static boolean isTrashed(Context c, Uri uri) {
    Bundle args = new Bundle();
    args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
    try (Cursor q = c.getContentResolver().query(uri, new String[] {"is_trashed"}, args, null)) {
      return q != null && q.moveToFirst() && q.getInt(0) == 1;
    }
  }

  public static void ensureTrashSafe(Context c, JobQueue.Job j) throws Exception {
    if (!j.state.canTrash() || j.outputUri == null) throw new IOException("Copy is not ready");
    if (!j.sourceHash.equals(Files.hash(Files.original(c, j.item.uri))))
      throw new IOException("Original changed since compression; keep both");
    if (!j.outputHash.equals(
        Files.hash(c.getContentResolver().openInputStream(Uri.parse(j.outputUri)))))
      throw new IOException("Compressed copy changed or is missing");
  }

  public static void finishTrash(Context c, JobQueue db, JobQueue.Job j) throws Exception {
    if (!isTrashed(c, j.item.uri)) {
      db.state(j.id, JobState.DONE, "Kept both · Trash was not approved");
      return;
    }
    db.state(j.id, JobState.ORIGINAL_TRASHED, "Original is in system Trash");
    String ext = j.mime.equals("video/mp4") ? "mp4" : j.mime.equals("image/heic") ? "heic" : "jpg";
    ContentValues name = new ContentValues();
    name.put("_display_name", j.item.base() + "." + ext);
    c.getContentResolver().update(Uri.parse(j.outputUri), name, null, null);
  }

  public static void finishRestore(Context c, JobQueue db, JobQueue.Job j) throws Exception {
    if (isTrashed(c, j.item.uri)) throw new IOException("Restore was not approved");
    if (!j.sourceHash.equals(Files.hash(Files.original(c, j.item.uri))))
      throw new IOException("Restored original cannot be verified; both files retained");
    if (j.outputUri != null) {
      Uri copy = Uri.parse(j.outputUri);
      boolean exists;
      try (Cursor q = c.getContentResolver().query(copy, new String[] {"_id"}, null, null, null)) {
        exists = q != null && q.moveToFirst();
      }
      if (exists) {
        if (!j.outputHash.equals(Files.hash(c.getContentResolver().openInputStream(copy))))
          throw new IOException("Compressed copy changed; retained for safety");
        c.getContentResolver().delete(copy, null, null);
      }
    }
    db.state(j.id, JobState.RESTORED, "Original restored; Compact copy removed");
  }

  public static void prepareRestore(Context c, JobQueue.Job j) throws Exception {
    if (j.outputUri == null) return;
    Uri copy = Uri.parse(j.outputUri);
    if (!j.outputHash.equals(Files.hash(c.getContentResolver().openInputStream(copy))))
      throw new IOException("Compact copy changed; retain both and restore from system Trash");
    ContentValues v = new ContentValues();
    v.put("_display_name", j.publishName);
    c.getContentResolver().update(copy, v, null, null);
  }
}
