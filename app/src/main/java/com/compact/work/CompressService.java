package com.compact.work;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import com.compact.MainActivity;
import com.compact.media.*;
import com.compact.photo.*;
import com.compact.util.Files;
import com.compact.video.*;
import java.io.*;

public final class CompressService extends Service {
  public static volatile boolean running;
  public static volatile String status = "Ready";
  public static volatile double progress;
  public static volatile long activeId, activeStarted;
  private volatile boolean quit, stopped, paused;
  private Thread worker;
  private JobQueue db;
  private PowerManager.WakeLock wake;
  private long lastNotice;
  private static final int NOTIFICATION = 71;

  private static final class Yield extends Exception {}

  public void onCreate() {
    super.onCreate();
    db = new JobQueue(this);
    wake =
        ((PowerManager) getSystemService(POWER_SERVICE))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Compact:encode");
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel(
            "processing", "Compression progress", NotificationManager.IMPORTANCE_LOW));
  }

  public int onStartCommand(Intent i, int flags, int start) {
    String action = i == null ? "resume" : i.getAction();
    if ("pause".equals(action)) paused = true;
    else if ("stop".equals(action)) stopped = true;
    else paused = false;
    if (Build.VERSION.SDK_INT >= 35)
      startForeground(
          NOTIFICATION, notice("Preparing"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
    else
      startForeground(
          NOTIFICATION, notice("Preparing"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    if (worker == null) {
      running = true;
      worker = new Thread(this::loop, "Compact-worker");
      worker.start();
    }
    return START_NOT_STICKY;
  }

  public IBinder onBind(Intent i) {
    return null;
  }

  public void onTimeout(int startId, int type) {
    quit = true;
    status = "Android processing limit reached. Tap Resume later.";
    if (worker != null) worker.interrupt();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  public void onDestroy() {
    quit = true;
    if (worker != null) worker.interrupt();
    super.onDestroy();
  }

  private void loop() {
    try {
      Replacer.recover(this, db);
      cleanAll();
      while (!quit) {
        if (stopped) {
          for (JobQueue.Job j : db.list())
            if (j.state == JobState.PENDING)
              db.state(j.id, JobState.SKIPPED, "Stopped by you · original retained");
          break;
        }
        JobQueue.Job j = db.next();
        if (j == null) break;
        activeId = j.id;
        long needed =
            (j.item.video ? 0 : j.item.size)
                + (long) (j.item.estimate(j.mode, j.heic) * 2.4)
                + 200L * 1024 * 1024;
        String gate = paused ? "Paused by you" : Scheduler.gate(this, j.charging, needed);
        if (gate != null) {
          update(gate);
          Thread.sleep(5000);
          continue;
        }
        File dir = new File(getCacheDir(), "work/" + j.id);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create workspace");
        try {
          activeStarted = SystemClock.elapsedRealtime();
          wake.acquire(6 * 60 * 60 * 1000L);
          check(j);
          update("Analysing " + j.item.name);
          db.state(j.id, JobState.ANALYSED, "");
          // Videos are read in place when the readable file is byte-identical to the original, so a
          // nearly full phone does not need room for a second full copy. Photos use a private snapshot.
          String[] directHash = new String[1];
          File source = j.item.video ? inPlace(j, directHash) : null;
          String sourceHash = directHash[0];
          if (source == null) {
            source = new File(dir, "source");
            sourceHash = Files.copy(Files.original(this, j.item.uri), source);
          }
          if (source.length() != j.item.size)
            throw new IOException("Original size changed; scan again");
          ContentValues hash = new ContentValues();
          hash.put("source_hash", sourceHash);
          db.update(j.id, hash);
          check(j);
          db.state(j.id, JobState.ENCODING, "");
          update("Compressing " + j.item.name);
          progress = 0;
          File result;
          String mime;
          double score, min;
          if (j.item.video) {
            VideoTranscoder.Result r =
                VideoTranscoder.compress(
                    source,
                    dir,
                    j.mode,
                    new VideoTranscoder.Control() {
                      public void check() throws Exception {
                        CompressService.this.check(j);
                      }

                      public void progress(double p) {
                        progress = p;
                        update("Compressing " + j.item.name);
                      }
                    });
            result = r.file;
            mime = "video/mp4";
            score = min = r.score;
          } else {
            PhotoEngine.Result r =
                PhotoEngine.compress(source, dir, j.mode, j.heic, () -> check(j));
            result = r.file;
            mime = r.mime;
            score = r.score;
            min = r.min;
          }
          db.state(j.id, JobState.ENCODED, "");
          check(j);
          ContentValues verified = new ContentValues();
          verified.put("output_size", result.length());
          verified.put("output_hash", Files.hash(result));
          verified.put("output_mime", mime);
          verified.put("score", score);
          verified.put("min_score", min);
          verified.put("state", "VERIFIED");
          db.update(j.id, verified);
          if (getCacheDir().getUsableSpace() < result.length() + 200L * 1024 * 1024)
            throw new IOException("Insufficient free storage to publish; original retained");
          update("Saving verified copy");
          Replacer.publish(this, db, db.get(j.id), result);
          progress = 1;
        } catch (Yield e) {
          db.state(j.id, JobState.PENDING, "Paused safely; current file will restart");
        } catch (OutOfMemoryError e) {
          db.state(j.id, JobState.SKIPPED, "Too large for available memory; original retained");
        } catch (Exception e) {
          JobQueue.Job current = db.get(j.id);
          if (current.state == JobState.PUBLISHING) {
            try {
              Replacer.recover(this, db);
              if (db.get(j.id).state == JobState.PENDING)
                db.state(j.id, JobState.FAILED, "Could not publish: " + message(e));
            } catch (Exception recovery) {
              android.util.Log.e("Compact", "Recovery pending", recovery);
              quit = true;
            }
          } else
            db.state(
                j.id, e instanceof IOException ? JobState.SKIPPED : JobState.FAILED, message(e));
        } finally {
          if (wake.isHeld()) wake.release();
          clean(dir);
        }
      }
      status = quit ? status : "Finished · open report";
    } catch (Exception e) {
      status = "Paused: " + message(e);
    } finally {
      running = false;
      activeId = 0;
      if (wake.isHeld()) wake.release();
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
    }
  }

  /** The original's file path when it can be read directly and matches the original bytes. */
  private File inPlace(JobQueue.Job j, String[] hash) {
    try (android.database.Cursor c =
        getContentResolver()
            .query(j.item.uri, new String[] {android.provider.MediaStore.MediaColumns.DATA}, null, null, null)) {
      if (c == null || !c.moveToFirst() || c.getString(0) == null) return null;
      File f = new File(c.getString(0));
      if (!f.canRead() || f.length() != j.item.size) return null;
      // Without location permission the file path can be redacted; only use it if identical.
      String direct = Files.hash(new FileInputStream(f));
      if (!direct.equals(Files.hash(Files.original(this, j.item.uri)))) return null;
      hash[0] = direct;
      return f;
    } catch (Exception e) {
      return null;
    }
  }

  private void check(JobQueue.Job j) throws Exception {
    if (quit || stopped || paused || Thread.currentThread().isInterrupted()) throw new Yield();
    String gate = Scheduler.gate(this, j.charging, 0);
    if (gate != null) {
      update(gate);
      throw new Yield();
    }
  }

  private void update(String s) {
    status = s;
    long now = SystemClock.elapsedRealtime();
    if (now - lastNotice > 1000) {
      lastNotice = now;
      getSystemService(NotificationManager.class).notify(NOTIFICATION, notice(s));
    }
  }

  private Notification notice(String text) {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this, "processing")
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Compact")
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(true)
        .setProgress(100, (int) (progress * 100), progress == 0)
        .build();
  }

  private static String message(Throwable e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  private void cleanAll() {
    File root = new File(getCacheDir(), "work");
    File[] dirs = root.listFiles();
    if (dirs != null) for (File d : dirs) clean(d);
  }

  private void clean(File dir) {
    File[] files = dir.listFiles();
    if (files != null) for (File f : files) if (f.isFile()) Files.discard(f);
    Files.discard(dir);
  }
}
