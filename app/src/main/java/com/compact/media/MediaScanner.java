package com.compact.media;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import java.util.*;

public final class MediaScanner {
  public static List<MediaItem> scan(Context c, boolean allFolders) throws Exception {
    List<MediaItem> out = new ArrayList<>();
    query(c, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, allFolders, out);
    query(c, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, allFolders, out);
    Set<String> produced = new HashSet<>();
    try (com.compact.work.JobQueue db = new com.compact.work.JobQueue(c)) {
      for (com.compact.work.JobQueue.Job j : db.list()) {
        if (j.outputUri != null) produced.add(j.outputUri);
        // Originals already compressed or still queued are not offered again (skipped/failed are).
        if (j.state != com.compact.work.JobState.SKIPPED
            && j.state != com.compact.work.JobState.FAILED
            && j.state != com.compact.work.JobState.RESTORED) produced.add(j.item.uri.toString());
      }
    }
    out.removeIf(item -> produced.contains(item.uri.toString()));
    out.sort((a, b) -> Long.compare(b.size, a.size));
    return out;
  }

  private static void query(
      Context c, Uri collection, boolean video, boolean all, List<MediaItem> out) {
    String[] cols = {
      "_id",
      "_display_name",
      "relative_path",
      "_size",
      "mime_type",
      "datetaken",
      "date_modified",
      "width",
      "height",
      video ? "duration" : "orientation",
      "owner_package_name",
      "volume_name"
    };
    String filter =
        "is_pending=0 AND is_trashed=0 AND _size>=?" + (all ? "" : " AND relative_path LIKE ?");
    String[] args =
        all
            ? new String[] {video ? "5242880" : "307200"}
            : new String[] {video ? "5242880" : "307200", "DCIM/Camera%"};
    try (Cursor cursor =
        c.getContentResolver().query(collection, cols, filter, args, "_size DESC")) {
      if (cursor == null) throw new IllegalStateException("Media library unavailable");
      while (cursor.moveToNext()) {
        String owner = cursor.getString(10), name = cursor.getString(1), mime = cursor.getString(4);
        if (c.getPackageName().equals(owner)
            || name == null
            || name.contains(" (compact)")
            || mime == null) continue;
        if (c.getPackageName().endsWith(".qa") && !name.startsWith("CompactTest_")) continue;
        if (!video && !mime.equals("image/jpeg")) continue;
        MediaItem m = new MediaItem();
        String volume = cursor.getString(11);
        Uri volumeCollection =
            video
                ? MediaStore.Video.Media.getContentUri(volume)
                : MediaStore.Images.Media.getContentUri(volume);
        m.uri = ContentUris.withAppendedId(volumeCollection, cursor.getLong(0));
        m.name = name;
        m.path = cursor.getString(2);
        m.size = cursor.getLong(3);
        m.mime = mime;
        m.dateTaken = cursor.getLong(5);
        m.dateModified = cursor.getLong(6);
        m.width = cursor.getInt(7);
        m.height = cursor.getInt(8);
        m.video = video;
        if (video) {
          m.duration = cursor.getLong(9);
          probeEstimate(c, m);
        } else m.orientation = cursor.getInt(9);
        out.add(m);
      }
    } catch (SecurityException e) {
      throw new SecurityException(
          "Grant photo and video access in Permissions to scan the library.", e);
    }
  }

  private static void probeEstimate(Context c, MediaItem m) {
    android.media.MediaExtractor e = new android.media.MediaExtractor();
    try {
      e.setDataSource(c, m.uri, null);
      m.audioBitrate = 0;
      for (int i = 0; i < e.getTrackCount(); i++) {
        android.media.MediaFormat f = e.getTrackFormat(i);
        String mime = f.getString(android.media.MediaFormat.KEY_MIME);
        if (mime != null
            && mime.startsWith("video/")
            && f.containsKey(android.media.MediaFormat.KEY_FRAME_RATE))
          m.fps = f.getNumber(android.media.MediaFormat.KEY_FRAME_RATE).floatValue();
        if (mime != null && mime.startsWith("audio/"))
          m.audioBitrate +=
              f.containsKey(android.media.MediaFormat.KEY_BIT_RATE)
                  ? f.getInteger(android.media.MediaFormat.KEY_BIT_RATE)
                  : 128000;
      }
    } catch (Exception ignored) {
      /* Estimate only; unsupported files are diagnosed by the engine. */
    } finally {
      e.release();
    }
  }
}
