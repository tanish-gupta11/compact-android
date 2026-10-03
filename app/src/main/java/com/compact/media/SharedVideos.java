package com.compact.media;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import com.compact.util.Files;
import com.compact.work.*;
import java.io.IOException;
import java.util.*;

/** Resolves a Gallery share to its local MediaStore original before using the existing queue. */
public final class SharedVideos {
  public static ArrayList<Uri> streams(Intent intent) throws IOException {
    LinkedHashSet<Uri> found = new LinkedHashSet<>();
    if (intent == null) return new ArrayList<>();
    String action = intent.getAction();
    if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action))
      return new ArrayList<>();
    try {
      if (Intent.ACTION_SEND.equals(action)) {
        Parcelable value = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (value instanceof Uri) found.add((Uri) value);
      } else {
        ArrayList<Parcelable> values = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (values != null) for (Parcelable value : values)
          if (value instanceof Uri) found.add((Uri) value);
      }
      // Some Gallery apps put the stream only in ClipData.
      if (found.isEmpty() && intent.getClipData() != null) {
        ClipData clip = intent.getClipData();
        if (clip.getItemCount() > 100) throw new IOException("Share up to 100 videos at a time.");
        for (int n = 0; n < clip.getItemCount(); n++) {
          Uri uri = clip.getItemAt(n).getUri();
          if (uri != null) found.add(uri);
        }
      }
    } catch (android.os.BadParcelableException | ClassCastException e) {
      throw new IOException("The Gallery sent an unreadable share. Try sharing the video again.", e);
    }
    if (found.isEmpty()) throw new IOException("No video was attached to this share.");
    if (found.size() > 100) throw new IOException("Share up to 100 videos at a time.");
    for (Uri uri : found)
      if (!"content".equals(uri.getScheme()))
        throw new IOException("Share a video stored on this phone from Gallery or Files.");
    return new ArrayList<>(found);
  }

  public static MediaItem resolve(Context c, Uri shared) throws Exception {
    Uri media = "media".equals(shared.getAuthority()) ? shared : null;
    if (media == null) {
      try { media = MediaStore.getMediaUri(c, shared); }
      catch (IllegalArgumentException | SecurityException ignored) { }
    }
    MediaItem item;
    if (media != null) item = read(c, media);
    else {
      // OEM Gallery providers may expose a temporary stream rather than a MediaStore URI.
      // A name alone is insufficient: require one local candidate with exactly matching bytes.
      String name = null;
      long size = -1;
      try (Cursor q = c.getContentResolver().query(shared,
          new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
        if (q != null && q.moveToFirst()) { name = q.getString(0); size = q.getLong(1); }
      }
      if (name == null || size <= 0) throw unavailable();
      ArrayList<Uri> candidates = new ArrayList<>();
      Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
      try (Cursor q = c.getContentResolver().query(collection,
          new String[] {"_id", "volume_name"},
          "_display_name=? AND _size=? AND is_pending=0 AND is_trashed=0",
          new String[] {name, Long.toString(size)}, null)) {
        if (q != null) while (q.moveToNext()) {
          if (candidates.size() == 5) throw unavailable();
          candidates.add(ContentUris.withAppendedId(
              MediaStore.Video.Media.getContentUri(q.getString(1)), q.getLong(0)));
        }
      }
      if (candidates.isEmpty()) throw unavailable();
      String sharedHash = Files.hash(c.getContentResolver().openInputStream(shared));
      item = null;
      for (Uri candidate : candidates)
        if (sharedHash.equals(Files.hash(c.getContentResolver().openInputStream(candidate)))) {
          if (item != null) throw unavailable();
          item = read(c, candidate);
        }
      if (item == null) throw unavailable();
    }
    try (JobQueue db = new JobQueue(c)) {
      for (JobQueue.Job job : db.list())
        if (Objects.equals(job.outputUri, item.uri.toString())
            || (job.item.uri.equals(item.uri) && job.state != JobState.SKIPPED
                && job.state != JobState.FAILED && job.state != JobState.RESTORED))
          throw new IOException(item.name + " is already queued or compressed. Check Report.");
    }
    return item;
  }

  private static IOException unavailable() {
    return new IOException("This share could not be matched to a local video. Download it to this phone "
        + "first, then share the local video from Gallery or Files.");
  }

  private static MediaItem read(Context c, Uri uri) throws Exception {
    if (!"media".equals(uri.getAuthority())) throw unavailable();
    long requestedId;
    try { requestedId = ContentUris.parseId(uri); }
    catch (NumberFormatException | UnsupportedOperationException e) { throw unavailable(); }
    if (requestedId <= 0) throw unavailable();
    try (Cursor q = c.getContentResolver().query(uri,
        new String[] {"_id", "volume_name", "mime_type", "_display_name", "relative_path", "_size",
            "datetaken", "date_modified", "width", "height", "orientation", "duration",
            "is_pending", "is_trashed", "owner_package_name"}, null, null, null)) {
      if (q == null || !q.moveToFirst() || q.getLong(0) != requestedId) throw unavailable();
      String mime = q.getString(2), volume = q.getString(1), name = q.getString(3);
      if (mime == null || !mime.startsWith("video/") || volume == null || "internal".equals(volume)
          || q.getInt(12) != 0 || q.getInt(13) != 0 || q.getLong(5) <= 0
          || name == null || q.getString(4) == null)
        throw new IOException("Share an available video stored on this phone.");
      if (c.getPackageName().equals(q.getString(14)) || name.contains(" (compact)"))
        throw new IOException("This is already a Compact copy. Check Report.");
      if (c.getPackageName().endsWith(".qa") && !name.startsWith("CompactTest_"))
        throw new IOException("QA accepts only CompactTest_ videos.");
      MediaItem item = new MediaItem();
      item.uri = ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(volume), q.getLong(0));
      item.video = true;
      item.mime = mime;
      item.name = name;
      item.path = q.getString(4);
      item.size = q.getLong(5);
      item.dateTaken = q.getLong(6);
      item.dateModified = q.getLong(7);
      item.width = q.getInt(8);
      item.height = q.getInt(9);
      item.orientation = q.getInt(10);
      item.duration = q.getLong(11);
      MediaScanner.probeEstimate(c, item);
      return item;
    }
  }
}
