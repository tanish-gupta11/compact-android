package com.compact;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.ExifInterface;
import android.os.*;
import com.compact.media.*;
import com.compact.photo.*;
import com.compact.quality.*;
import com.compact.work.*;
import java.io.*;
import java.util.*;

/** Opt-in native checks. Only private generated CompactTest_* files; no library queries. */
public final class QaChecks extends Instrumentation {
  public void onCreate(Bundle b) {
    super.onCreate(b);
    start();
  }

  public void onStart() {
    Bundle result = new Bundle();
    StringBuilder log = new StringBuilder();
    int status = Activity.RESULT_OK;
    File dir = null;
    try {
      Context c = getTargetContext();
      if (!c.getPackageName().equals("com.compact.qa"))
        throw new SecurityException("QA package only");
      dir = new File(c.getCacheDir(), "CompactTest_native_" + System.currentTimeMillis());
      if (!dir.mkdirs()) throw new IOException("No test directory");
      File src = new File(dir, "CompactTest_source.jpg");
      Bitmap b = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(b);
      Paint paint = new Paint();
      for (int y = 0; y < 768; y++) {
        paint.setColor(Color.rgb(y % 256, (y / 3) % 256, (y / 7) % 256));
        canvas.drawRect(0, y, 1024, y + 1, paint);
      }
      paint.setColor(Color.WHITE);
      paint.setTextSize(50);
      canvas.drawText("CompactTest · detail 12345", 80, 240, paint);
      try (FileOutputStream out = new FileOutputStream(src)) {
        b.compress(Bitmap.CompressFormat.JPEG, 100, out);
      }
      b.recycle();
      ExifInterface exif = new ExifInterface(src);
      exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05");
      exif.setAttribute(ExifInterface.TAG_ORIENTATION, "6");
      exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "12/1,20/1,24/1");
      exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
      exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "56/1,46/1,48/1");
      exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E");
      exif.saveAttributes();
      PhotoQuality.Score identity = PhotoQuality.compare(src, src, true);
      if (identity.mean != 1) throw new AssertionError("SSIM identity");
      File lossless = new File(dir, "CompactTest_lossless.jpg");
      com.compact.util.Files.write(
          lossless, LosslessJpeg.optimize(com.compact.util.Files.read(src)));
      PhotoQuality.compare(src, lossless, true);
      ExifCopier.verify(src, lossless);
      log.append("LOSSLESS PASS ")
          .append(src.length())
          .append(" -> ")
          .append(lossless.length())
          .append(" bytes; pixels/EXIF identical\n");
      for (boolean heic : new boolean[] {false, true}) {
        File attempt = new File(dir, heic ? "heic" : "jpeg");
        attempt.mkdirs();
        try {
          PhotoEngine.Result r = PhotoEngine.compress(src, attempt, 0, heic, () -> {});
          log.append("SMART ")
              .append(r.mime)
              .append(" PASS ")
              .append(src.length())
              .append(" -> ")
              .append(r.file.length())
              .append(" SSIM=")
              .append(r.score)
              .append(" min=")
              .append(r.min)
              .append("\n");
        } catch (IOException skip) {
          log.append("SMART ")
              .append(heic ? "HEIC" : "JPEG")
              .append(" SKIPPED: ")
              .append(skip.getMessage())
              .append("\n");
        }
      }
      // Use a separately named test database, never the app's real queue.
      Context testContext =
          new android.content.ContextWrapper(c) {
            public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(
                String name,
                int mode,
                android.database.sqlite.SQLiteDatabase.CursorFactory factory,
                android.database.DatabaseErrorHandler error) {
              return super.openOrCreateDatabase("CompactTest_" + name, mode, factory, error);
            }

            public File getDatabasePath(String name) {
              return super.getDatabasePath("CompactTest_" + name);
            }
          };
      try (JobQueue q = new JobQueue(testContext)) {
        MediaItem m = new MediaItem();
        m.uri = android.net.Uri.parse("content://media/external_primary/images/media/999999999");
        m.name = "CompactTest_queue.jpg";
        m.path = "DCIM/Camera/";
        m.mime = "image/jpeg";
        q.enqueue(Collections.singletonList(m), 0, false, false, false);
        JobQueue.Job j = q.next();
        if (j == null) throw new AssertionError("Queue empty");
        q.state(j.id, JobState.ANALYSED, "");
        q.state(j.id, JobState.ENCODING, "");
        q.recover();
        if (q.get(j.id).state != JobState.PENDING) throw new AssertionError("Recovery");
      }
      c.deleteDatabase("CompactTest_compact.db");
      log.append("QUEUE native SQLite recovery PASS\n");
    } catch (Throwable e) {
      status = Activity.RESULT_CANCELED;
      log.append("FAIL ").append(android.util.Log.getStackTraceString(e));
    } finally {
      if (dir != null) clean(dir);
      getTargetContext().deleteDatabase("CompactTest_compact.db");
    }
    result.putString("stream", log.toString());
    result.putString("result", status == Activity.RESULT_OK ? "PASS (see skips)" : "FAIL");
    finish(status, result);
  }

  private static void clean(File dir) {
    File[] files = dir.listFiles();
    if (files != null)
      for (File f : files) {
        if (f.isDirectory()) clean(f);
        else f.delete();
      }
    dir.delete();
  }
}
