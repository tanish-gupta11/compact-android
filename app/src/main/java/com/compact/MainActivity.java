package com.compact;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import com.compact.media.*;
import com.compact.util.Ui;
import com.compact.work.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
  private LinearLayout root, body;
  private TextView live;
  private String page = "home";
  private JobQueue db;
  private SharedPreferences prefs;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final ExecutorService thumbnails = Executors.newFixedThreadPool(2);
  private final Handler handler = new Handler();
  private List<MediaItem> scanned = new ArrayList<>(), visible = new ArrayList<>();
  private final Set<String> selected = new HashSet<>();
  private int typeFilter, sizeFilter, dateFilter;
  private boolean busy, shownRunning;
  private String scanError;
  private ProgressDialog pendingDialog;
  private static final long SESSION_START = System.currentTimeMillis();
  private double photoRatio = -1, videoRatio = -1;
  private final Runnable ticker =
      new Runnable() {
        public void run() {
          if ("progress".equals(page) && live != null) {
            // Redraw when the service starts or stops so Pause/Resume match its real state.
            if (CompressService.running != shownRunning) show("progress");
            else live.setText(progressText());
          }
          handler.postDelayed(this, 1000);
        }
      };

  protected void onCreate(Bundle state) {
    super.onCreate(state);
    db = new JobQueue(this);
    prefs = getSharedPreferences("settings", 0);
    if (state != null) page = state.getString("page", "home");
    show(page);
    if (!CompressService.running
        && !prefs.contains("trashRequest")
        && !prefs.contains("restoreRequest")) {
      task(
          "Checking interrupted work…",
          () -> {
            Replacer.recover(this, db);
            java.io.File work = new java.io.File(getCacheDir(), "work");
            java.io.File[] dirs = work.listFiles();
            if (dirs != null)
              for (java.io.File dir : dirs) {
                java.io.File[] files = dir.listFiles();
                if (files != null)
                  for (java.io.File f : files) if (f.isFile()) com.compact.util.Files.discard(f);
                com.compact.util.Files.discard(dir);
              }
            runOnUiThread(() -> show(page));
          });
    }
  }

  protected void onResume() {
    super.onResume();
    handler.post(ticker);
    if (!busy && prefs.contains("trashRequest")) reconcileTrash();
    else if (!busy && prefs.contains("purgeRequest")) reconcilePurge();
    else if (!busy && prefs.contains("restoreRequest")) {
      long id = prefs.getLong("restoreRequest", 0);
      task(
          "Checking interrupted restore…",
          () -> {
            JobQueue.Job j = db.get(id);
            if (j != null && !Replacer.isTrashed(this, j.item.uri))
              Replacer.finishRestore(this, db, j);
            prefs.edit().remove("restoreRequest").commit();
            runOnUiThread(() -> show("recent"));
          });
    }
  }

  protected void onPause() {
    handler.removeCallbacks(ticker);
    super.onPause();
  }

  protected void onDestroy() {
    if (pendingDialog != null) pendingDialog.dismiss();
    io.execute(db::close);
    io.shutdown();
    thumbnails.shutdownNow();
    super.onDestroy();
  }

  protected void onSaveInstanceState(Bundle b) {
    b.putString("page", page);
    super.onSaveInstanceState(b);
  }

  public void onBackPressed() {
    if (!page.equals("home")) show("home");
    else super.onBackPressed();
  }

  private void shell(String title) {
    root = Ui.column(this);
    root.setBackgroundColor(Ui.BG);
    Ui.edge(this, root);
    setContentView(root);
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 8));
    TextView brand = Ui.text(this, title, 26, Ui.TEXT);
    header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    if (!page.equals("home")) {
      Button back = Ui.button(this, "Home", false);
      back.setOnClickListener(v -> show("home"));
      header.addView(back);
    }
    root.addView(header);
    body = Ui.column(this);
    body.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 20));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
  }

  private void show(String next) {
    if (isDestroyed()) return;
    refreshEstimates();
    page = next;
    shell(
        next.equals("home")
            ? "Compact"
            : next.equals("recent")
                ? "Recently replaced"
                : Character.toUpperCase(next.charAt(0)) + next.substring(1));
    switch (next) {
      case "review":
        review();
        break;
      case "progress":
        progress();
        break;
      case "report":
        report(false);
        break;
      case "recent":
        report(true);
        break;
      case "settings":
        settings();
        break;
      default:
        home();
    }
  }

  private void label(String s) {
    Ui.add(body, Ui.text(this, s, 15, Ui.MUTED));
  }

  private void action(String name, boolean primary, Runnable r) {
    Button b = Ui.button(this, name, primary);
    b.setOnClickListener(v -> r.run());
    Ui.add(body, b);
  }

  private void home() {
    label("MORE ROOM FOR YOUR MEMORIES");
    LinearLayout hero = Ui.card(this);
    Ui.add(hero, Ui.text(this, "Keep the moment.\nLighten the file.", 30, Ui.TEXT));
    Ui.add(
        hero,
        Ui.text(
            this,
            "Compress on your phone. Full resolution.\nPrivate by design, entirely offline.",
            15,
            Ui.MUTED));
    long total = 0, saving = 0;
    for (MediaItem m : scanned) {
      total += m.size;
      saving += Math.max(0, m.size - estimate(m));
    }
    Ui.add(
        hero,
        Ui.text(
            this,
            scanned.isEmpty()
                ? "Your camera library"
                : Ui.size(total) + " in " + scanned.size() + " candidates",
            22,
            Ui.ACCENT));
    Ui.add(
        hero,
        Ui.text(
            this,
            scanned.isEmpty()
                ? "Scan to find photos and videos worth compressing."
                : "Best-case bitrate estimate: up to ≈ " + Ui.size(saving)
                    + ". Smart may skip videos that lose detail.",
            14,
            Ui.MUTED));
    Ui.add(body, hero);
    if (scanError != null) label(scanError);
    action("Scan camera library", true, this::scan);
    if (!scanned.isEmpty())
      action("Review " + scanned.size() + " files", false, () -> show("review"));
    action(
        CompressService.running ? "View compression progress" : "Queue / resume",
        false,
        () -> show("progress"));
    action("Reports & verified copies", false, () -> show("report"));
    action("Recently replaced", false, () -> show("recent"));
    action("Settings & how it works", false, () -> show("settings"));
    label(
        "Smart compression changes pixels slightly. Every result must pass quality checks. Only"
            + " Lossless photo mode preserves every pixel.");
  }

  private boolean permissions() {
    if (Build.VERSION.SDK_INT >= 33)
      return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
              == PackageManager.PERMISSION_GRANTED
          && checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
              == PackageManager.PERMISSION_GRANTED;
    return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void requestAccess() {
    List<String> p = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= 33) {
      p.add(Manifest.permission.READ_MEDIA_IMAGES);
      p.add(Manifest.permission.READ_MEDIA_VIDEO);
      if (Build.VERSION.SDK_INT >= 34) p.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
    } else p.add(Manifest.permission.READ_EXTERNAL_STORAGE);
    requestPermissions(p.toArray(new String[0]), 40);
  }

  public void onRequestPermissionsResult(int code, String[] p, int[] r) {
    super.onRequestPermissionsResult(code, p, r);
    if (code == 40) {
      if (permissions()) scan();
      else {
        scanError =
            "Full photo and video access is needed for a complete library scan. Selected-only"
                + " access does not include your whole camera folder.";
        show("home");
      }
    } else if (code == 41) {
      if (checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
          == PackageManager.PERMISSION_GRANTED) options();
      else
        message(
            "Location metadata access is needed to preserve existing photo GPS. No new location is"
                + " recorded.");
    }
  }

  private void scan() {
    if (!permissions()) {
      new AlertDialog.Builder(this)
          .setTitle("Read your camera library")
          .setMessage(
              "Allow photo and video access to list files. Android selected-only access gives an"
                  + " incomplete scan. Files are changed only after you select them and start"
                  + " compression.")
          .setPositiveButton("Continue", (d, w) -> requestAccess())
          .setNegativeButton("Cancel", null)
          .show();
      return;
    }
    task(
        "Scanning camera library…",
        () -> {
          List<MediaItem> items = MediaScanner.scan(this, prefs.getBoolean("allFolders", false));
          runOnUiThread(
              () -> {
                scanned = items;
                selected.clear();
                scanError = null;
                show("review");
              });
        });
  }

  private Spinner spinner(String[] labels, int index) {
    Spinner s = new Spinner(this);
    ArrayAdapter<String> a =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
    s.setAdapter(a);
    s.setSelection(index);
    return s;
  }

  private void review() {
    label(
        "Choose files to compress. Sizes with ≈ are estimates; files that fail verification are"
            + " kept.");
    Spinner
        type = spinner(new String[] {"Photos & videos", "Photos only", "Videos only"}, typeFilter),
        size = spinner(new String[] {"All sizes", "At least 10 MB", "At least 100 MB"}, sizeFilter),
        age =
            spinner(
                new String[] {"Any date", "Older than 7 days", "Older than 30 days"}, dateFilter);
    LinearLayout filters = new LinearLayout(this);
    for (Spinner s : new Spinner[] {type, size, age}) {
      filters.addView(s, new LinearLayout.LayoutParams(0, -2, 1));
      s.setOnItemSelectedListener(
          new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
              int t = type.getSelectedItemPosition(), z = size.getSelectedItemPosition(),
                  a = age.getSelectedItemPosition();
              if (t != typeFilter || z != sizeFilter || a != dateFilter) {
                typeFilter = t;
                sizeFilter = z;
                dateFilter = a;
                show("review");
              }
            }

            public void onNothingSelected(AdapterView<?> parent) {}
          });
    }
    Ui.add(body, filters);
    visible = new ArrayList<>();
    long cutoff =
        dateFilter == 0
            ? Long.MAX_VALUE
            : System.currentTimeMillis() - (dateFilter == 1 ? 7L : 30L) * 86400000;
    for (MediaItem m : scanned)
      if ((typeFilter == 0 || (typeFilter == 1 && !m.video) || (typeFilter == 2 && m.video))
          && m.size >= (sizeFilter == 0 ? 0 : sizeFilter == 1 ? 10L * 1048576 : 100L * 1048576)
          && m.dateTaken <= cutoff) visible.add(m);
    int mode = prefs.getInt("mode", 0);
    boolean heic = prefs.getBoolean("heic", true);
    visible.sort((a, b) -> Long.compare(b.size - estimate(b), a.size - estimate(a)));
    LinearLayout bar = new LinearLayout(this);
    Button all = Ui.button(this, "Select all", false), none = Ui.button(this, "Clear", false);
    all.setOnClickListener(v -> {
      for (MediaItem m : visible) selected.add(m.uri.toString());
      show("review");
    });
    none.setOnClickListener(v -> {
      selected.clear();
      show("review");
    });
    LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
    half.rightMargin = Ui.dp(this, 8);
    bar.addView(all, half);
    bar.addView(none, new LinearLayout.LayoutParams(0, -2, 1));
    Ui.add(body, bar);
    TextView count = Ui.text(this, "", 15, Ui.MUTED);
    Button go = Ui.button(this, "", true);
    Runnable refresh = () -> {
      long before = 0, after = 0;
      for (MediaItem m : scanned)
        if (selected.contains(m.uri.toString())) {
          before += m.size;
          after += estimate(m);
        }
      count.setText(visible.size() + " shown · " + selected.size() + " selected · best-case up to ≈ "
          + Ui.size(Math.max(0, before - after)) + " (may save less or skip)");
      go.setText(selected.isEmpty() ? "Select files to compress" : "Compress " + selected.size() + (selected.size() == 1 ? " file…" : " files…"));
    };
    refresh.run();
    go.setOnClickListener(v -> options());
    Ui.add(body, count);
    Ui.add(body, go);
    if (visible.isEmpty())
      label(
          "No eligible files. JPEG photos must be at least 300 KB; videos at least 5 MB. RAW, HEIC"
              + " and Compact-created copies are excluded.");
    // A ListView owns candidate rows, so a large camera library does not inflate thousands of
    // views.
    ListView list = new ListView(this);
    list.setDividerHeight(Ui.dp(this, 8));
    list.setAdapter(
        new BaseAdapter() {
          public int getCount() {
            return visible.size();
          }

          public Object getItem(int n) {
            return visible.get(n);
          }

          public long getItemId(int n) {
            return n;
          }

          public View getView(int n, View old, android.view.ViewGroup group) {
            MediaItem m = visible.get(n);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            ImageView thumb = new ImageView(MainActivity.this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setContentDescription("Preview " + m.name);
            thumb.setTag(m.uri);
            thumb.setOnClickListener(v -> {
              Intent view = new Intent(Intent.ACTION_VIEW);
              view.setDataAndType(m.uri, m.mime);
              view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
              try { startActivity(view); }
              catch (android.content.ActivityNotFoundException error) {
                message("No viewer is available for this file.");
              }
            });
            row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 76), Ui.dp(MainActivity.this, 76)));
            thumbnails.execute(() -> {
              try {
                android.graphics.Bitmap bitmap = getContentResolver().loadThumbnail(m.uri,
                    new android.util.Size(152, 152), null);
                runOnUiThread(() -> {
                  if (!isDestroyed() && m.uri.equals(thumb.getTag())) thumb.setImageBitmap(bitmap);
                  else bitmap.recycle();
                });
              } catch (Exception ignored) { }
            });
            CheckBox choice = new CheckBox(MainActivity.this);
            choice.setTextColor(Ui.TEXT);
            choice.setText(
                m.name
                    + "\n"
                    + (m.video ? "VIDEO" : "PHOTO")
                    + "  "
                    + Ui.size(m.size)
                    + " → ≈ "
                    + (m.video
                        ? Ui.size(Math.min(estimate(m), (long) (m.size * .85))) + "–"
                            + Ui.size((long) (m.size * .85)) + " or skip"
                        : Ui.size(estimate(m))));
            choice.setPadding(8, 16, 8, 16);
            choice.setChecked(selected.contains(m.uri.toString()));
            choice.setOnCheckedChangeListener(
                (button, on) -> {
                  if (on) selected.add(m.uri.toString());
                  else selected.remove(m.uri.toString());
                  refresh.run();
                });
            row.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> choice.setChecked(!choice.isChecked()));
            return row;
          }
        });
    body.addView(list, new LinearLayout.LayoutParams(-1, Ui.dp(this, 420)));
  }

  private void options() {
    if (busy) return;
    if (selected.isEmpty()) {
      message("Select at least one file first.");
      return;
    }
    if (checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.ACCESS_MEDIA_LOCATION}, 41);
      return;
    }
    LinearLayout content = Ui.column(this);
    int pad = Ui.dp(this, 20);
    content.setPadding(pad, pad, pad, pad);
    Spinner mode =
        spinner(
            new String[] {
              "Smart · verified perceptual quality",
              "Lossless · pixel-identical JPEG only",
              "Max saving · some detail may change"
            },
            prefs.getInt("mode", 0));
    Spinner format =
        spinner(
            new String[] {"HEIC · JPEG fallback if unsupported", "JPEG · widely compatible"},
            prefs.getBoolean("heic", true) ? 0 : 1);
    Spinner after =
        spinner(
            new String[] {
              "Review copies, then ask to Trash originals",
              "Keep originals alongside compressed copies"
            },
            prefs.getBoolean("trash", true) ? 0 : 1);
    Switch charging = new Switch(this);
    charging.setText("Only while charging");
    charging.setChecked(prefs.getBoolean("charging", false));
    Ui.add(content, mode);
    Ui.add(content, format);
    Ui.add(content, after);
    Ui.add(content, charging);
    Ui.add(
        content,
        Ui.text(
            this,
            "Trash retains originals for a system-controlled period (often 30 days). Storage is not"
                + " freed while originals remain there. Smart/Max are lossy; SSIM does not"
                + " guarantee invisible changes.",
            14,
            Ui.MUTED));
    new AlertDialog.Builder(this)
        .setTitle("Compress " + selected.size() + (selected.size() == 1 ? " file" : " files"))
        .setView(content)
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Start",
            (d, w) -> {
              int m = mode.getSelectedItemPosition();
              boolean h = format.getSelectedItemPosition() == 0,
                  t = after.getSelectedItemPosition() == 0,
                  ch = charging.isChecked();
              prefs
                  .edit()
                  .putInt("mode", m)
                  .putBoolean("heic", h)
                  .putBoolean("trash", t)
                  .putBoolean("charging", ch)
                  .apply();
              List<MediaItem> items = new ArrayList<>();
              for (MediaItem item : scanned)
                if (selected.contains(item.uri.toString())) items.add(item);
              db.enqueue(items, m, h, t, ch);
              if (Build.VERSION.SDK_INT >= 33
                  && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                      != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 42);
              service("resume");
              show("progress");
            })
        .show();
  }

  private void service(String command) {
    if (busy) return;
    try {
      startForegroundService(new Intent(this, CompressService.class).setAction(command));
    } catch (Exception e) {
      message("Cannot start background processing: " + e.getMessage());
    }
  }

  private void progress() {
    shownRunning = CompressService.running;
    live = Ui.text(this, progressText(), 22, Ui.TEXT);
    Ui.add(body, live);
    JobQueue.Job current = db.get(CompressService.activeId);
    if (current != null) {
      ImageView thumb = new ImageView(this);
      thumb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
      thumb.setContentDescription("Current file thumbnail");
      body.addView(thumb, new LinearLayout.LayoutParams(-1, Ui.dp(this, 160)));
      io.execute(
          () -> {
            try {
              android.graphics.Bitmap image =
                  getContentResolver()
                      .loadThumbnail(current.item.uri, new android.util.Size(320, 240), null);
              runOnUiThread(() -> thumb.setImageBitmap(image));
            } catch (Exception ignored) {
            }
          });
    }
    label(
        "Pause safely restarts the current file when resumed. Completed copies remain in your"
            + " report.");
    if (CompressService.running) {
      action("Pause", false, () -> {
        service("pause");
        handler.postDelayed(() -> show("progress"), 500);
      });
      action("Stop queue", false, () -> service("stop"));
    } else {
      action("Resume", true, () -> {
        service("resume");
        handler.postDelayed(() -> show("progress"), 500);
      });
    }
    action("View report", false, () -> show("report"));
  }

  private boolean activeVideo() {
    JobQueue.Job j = CompressService.activeId == 0 ? null : db.get(CompressService.activeId);
    return j != null && j.item.video && CompressService.running;
  }

  private String progressText() {
    List<JobQueue.Job> jobs = db.list();
    int done = 0;
    long saved = 0;
    for (JobQueue.Job j : jobs) {
      if (j.state.terminal()
          || j.state == JobState.PUBLISHED
          || j.state == JobState.ORIGINAL_TRASHED) done++;
      if (j.outputSize > 0 && j.outputUri != null && j.state != JobState.RESTORED)
        saved += Math.max(0, j.item.size - j.outputSize);
    }
    double p = CompressService.progress;
    String eta =
        p > .02 && p < 1
            ? "≈ "
                + Math.max(
                    1,
                    Math.round(
                        (SystemClock.elapsedRealtime() - CompressService.activeStarted)
                            * (1 - p)
                            / p
                            / 60000))
                + " min for this file"
            : "ETA available while encoding video";
    return CompressService.status
        + "\n\n"
        + done
        + " of "
        + jobs.size()
        + " files\n"
        + (activeVideo()
            ? Math.round(p * 100) + "% of current video · " + eta + "\n"
            : "")
        + Ui.size(saved)
        + " smaller copies";
  }

  private void report(boolean recent) {
    label(
        recent
            ? "System Trash is separate from some Xiaomi/HyperOS Gallery bins. Restore here while"
                + " Android still retains the original."
            : "Review quality before approving Trash. Smaller copies do not mean storage has"
                + " already been freed.");
    List<JobQueue.Job> jobs = db.list();
    boolean pending = false;
    for (JobQueue.Job j : jobs) if (j.state == JobState.PUBLISHED) pending = true;
    if (!recent && pending) action("Review & approve Trash (up to 100)", true, this::trash);
    if (recent) {
      long held = 0;
      for (JobQueue.Job j : jobs) if (j.state == JobState.ORIGINAL_TRASHED) held += j.item.size;
      if (held > 0) {
        label("Originals in Trash still use " + Ui.size(held) + " until Android empties it (often"
            + " after 30 days). Free it now to permanently delete them; this cannot be undone.");
        action("Free up " + Ui.size(held) + " now", true, this::purge);
      }
    }
    int count = 0;
    for (JobQueue.Job j : jobs) {
      if (recent && j.state != JobState.ORIGINAL_TRASHED) continue;
      count++;
      LinearLayout card = Ui.card(this);
      Ui.add(card, Ui.text(this, j.item.name, 17, Ui.TEXT));
      Ui.add(card, Ui.text(this, j.state + " · " + j.reason, 13, Ui.MUTED));
      if (j.outputSize > 0) {
        Ui.add(
            card,
            Ui.text(
                this,
                Ui.size(j.item.size)
                    + " → "
                    + Ui.size(j.outputSize)
                    + "\nSSIM "
                    + Ui.score(j.score)
                    + " · minimum "
                    + Ui.score(j.min),
                15,
                Ui.GREEN));
        if (j.outputUri != null && j.state != JobState.RESTORED) {
          String savedName = j.state == JobState.ORIGINAL_TRASHED
              || (j.state == JobState.DONE && j.trash)
              ? j.item.base() + ("image/heic".equals(j.mime) ? ".heic"
                  : "video/mp4".equals(j.mime) ? ".mp4" : ".jpg")
              : j.publishName;
          Ui.add(card, Ui.text(this,
              "Saved in " + j.item.path + savedName + "\nGallery date: "
                  + new java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                      .format(new Date(j.item.dateTaken)),
              13, Ui.MUTED));
          Button compare = Ui.button(this, "Compare", false);
          compare.setOnClickListener(
              v -> startActivity(new Intent(this, CompareActivity.class).putExtra("id", j.id)));
          Ui.add(card, compare);
          Button open = Ui.button(this, "Open saved copy", false);
          open.setOnClickListener(v -> {
            try {
              Intent view = new Intent(Intent.ACTION_VIEW)
                  .setDataAndType(Uri.parse(j.outputUri), j.mime)
                  .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
              startActivity(view);
            } catch (Exception e) {
              message("No viewer opened this copy. Check the saved path above in Gallery or Files.");
            }
          });
          Ui.add(card, open);
        }
      }
      if (!recent && j.state == JobState.DONE && !j.trash && j.outputUri != null) {
        Button reviewed = Ui.button(this, "After checking: move original to Trash", false);
        reviewed.setOnClickListener(v -> reviewForTrash(j));
        Ui.add(card, reviewed);
      }
      if (!recent && j.item.video && j.state == JobState.SKIPPED && j.mode == 0) {
        Button retry = Ui.button(this, "Try Max · keep original", false);
        retry.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Try Max on this video?")
            .setMessage("Max allows some visible softening. Compact will keep the original beside any"
                + " verified smaller copy; nothing will be moved to Trash. Compare both videos"
                + " and check the sound before deciding what to keep.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start Max", (dialog, which) -> {
              db.enqueue(Collections.singletonList(j.item), 2,
                  prefs.getBoolean("heic", true), false,
                  prefs.getBoolean("charging", false));
              service("resume");
              show("progress");
            })
            .show());
        Ui.add(card, retry);
      }
      if (j.state == JobState.ORIGINAL_TRASHED) {
        Button restore = Ui.button(this, "Restore original", false);
        restore.setOnClickListener(v -> restore(j));
        Ui.add(card, restore);
      }
      Ui.add(body, card);
    }
    if (count == 0) label(recent ? "No replaced originals yet." : "Your results will appear here.");
  }

  private void trash() {
    task(
        "Verifying originals and copies…",
        () -> {
          ArrayList<Uri> uris = new ArrayList<>();
          StringBuilder ids = new StringBuilder();
          for (JobQueue.Job j : db.list())
            if (j.state == JobState.PUBLISHED && uris.size() < 100) {
              Replacer.ensureTrashSafe(this, j);
              uris.add(j.item.uri);
              if (ids.length() > 0) ids.append(",");
              ids.append(j.id);
            }
          if (uris.isEmpty()) return;
          PendingIntent request = MediaStore.createTrashRequest(getContentResolver(), uris, true);
          prefs.edit().putString("trashRequest", ids.toString()).commit();
          runOnUiThread(() -> send(request, 51));
        });
  }

  private void reviewForTrash(JobQueue.Job j) {
    CheckBox checked = new CheckBox(this);
    checked.setText(j.item.video
        ? "I watched this saved copy, checked its sound, picture and orientation, and found it in Gallery or Files."
        : "I opened this saved copy, checked its detail and orientation, and found it in Gallery or Files.");
    checked.setPadding(24, 16, 24, 16);
    AlertDialog dialog = new AlertDialog.Builder(this)
        .setTitle("Move this original to system Trash?")
        .setMessage("Only " + j.item.name + " will be requested. Compact will recheck both files first. "
            + "The original can be restored while Android retains it (often about 30 days). "
            + "Do not permanently delete it until you have a separate backup of anything precious.")
        .setView(checked)
        .setNegativeButton("Keep original", null)
        .setPositiveButton("Verify and request Trash", (d, w) -> trashReviewed(j.id))
        .create();
    dialog.setOnShowListener(d -> {
      Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
      positive.setEnabled(false);
      checked.setOnCheckedChangeListener((button, yes) -> positive.setEnabled(yes));
    });
    dialog.show();
  }

  private void trashReviewed(long id) {
    task("Rechecking this original and saved copy…", () -> {
      JobQueue.Job j = db.get(id);
      Replacer.prepareReviewedTrash(this, db, j);
      j = db.get(id);
      Replacer.ensureTrashSafe(this, j);
      PendingIntent request = MediaStore.createTrashRequest(
          getContentResolver(), Collections.singletonList(j.item.uri), true);
      prefs.edit().putString("trashRequest", Long.toString(id)).commit();
      runOnUiThread(() -> send(request, 51));
    });
  }

  private void purge() {
    CheckBox checked = new CheckBox(this);
    checked.setText("I opened and played the saved copies, and backed up any irreplaceable originals.");
    checked.setPadding(24, 16, 24, 16);
    AlertDialog dialog = new AlertDialog.Builder(this)
        .setTitle("Permanently delete originals?")
        .setMessage("The compressed copies stay in your Gallery. The originals in Trash are deleted"
            + " for good and cannot be restored afterwards. Max is lossy; file checks cannot"
            + " guarantee you will like the picture or sound.")
        .setView(checked)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Continue", (d, w) -> task(
            "Checking compressed copies…",
            () -> {
              ArrayList<Uri> uris = new ArrayList<>();
              StringBuilder ids = new StringBuilder();
              for (JobQueue.Job j : db.list())
                if (j.state == JobState.ORIGINAL_TRASHED && uris.size() < 100) {
                  Replacer.ensurePurgeSafe(this, j);
                  uris.add(j.item.uri);
                  if (ids.length() > 0) ids.append(",");
                  ids.append(j.id);
                }
              if (uris.isEmpty()) return;
              PendingIntent request = MediaStore.createDeleteRequest(getContentResolver(), uris);
              prefs.edit().putString("purgeRequest", ids.toString()).commit();
              runOnUiThread(() -> send(request, 53));
            }))
        .create();
    dialog.setOnShowListener(d -> {
      Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
      positive.setEnabled(false);
      checked.setOnCheckedChangeListener((button, yes) -> positive.setEnabled(yes));
    });
    dialog.show();
  }

  private void reconcilePurge() {
    task(
        "Checking freed space…",
        () -> {
          for (String id : prefs.getString("purgeRequest", "").split(","))
            if (!id.isEmpty()) {
              JobQueue.Job j = db.get(Long.parseLong(id));
              if (j != null && j.state == JobState.ORIGINAL_TRASHED && !Replacer.exists(this, j.item.uri))
                db.state(j.id, JobState.DONE, "Replaced · original permanently deleted, space freed");
            }
          prefs.edit().remove("purgeRequest").commit();
          runOnUiThread(() -> show("recent"));
        });
  }

  private void restore(JobQueue.Job j) {
    task(
        "Preparing restore…",
        () -> {
          Replacer.prepareRestore(this, j);
          prefs.edit().putLong("restoreRequest", j.id).commit();
          PendingIntent request =
              MediaStore.createTrashRequest(
                  getContentResolver(), Collections.singletonList(j.item.uri), false);
          runOnUiThread(() -> send(request, 52));
        });
  }

  private void send(PendingIntent p, int code) {
    try {
      startIntentSenderForResult(p.getIntentSender(), code, null, 0, 0, 0);
    } catch (Exception e) {
      message(e.getMessage());
    }
  }

  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 51) {
      reconcileTrash();
    } else if (request == 53) {
      reconcilePurge();
    } else if (request == 52) {
      if (result != RESULT_OK) {
        prefs.edit().remove("restoreRequest").commit();
        return;
      }
      long id = prefs.getLong("restoreRequest", 0);
      task(
          "Verifying restored original…",
          () -> {
            JobQueue.Job j = db.get(id);
            if (j != null) Replacer.finishRestore(this, db, j);
            prefs.edit().remove("restoreRequest").commit();
            runOnUiThread(() -> show("recent"));
          });
    }
  }

  private void reconcileTrash() {
    task(
        "Checking system Trash results…",
        () -> {
          String ids = prefs.getString("trashRequest", "");
          for (String id : ids.split(","))
            if (!id.isEmpty()) {
              JobQueue.Job j = db.get(Long.parseLong(id));
              if (j != null && j.state == JobState.PUBLISHED) Replacer.finishTrash(this, db, j);
            }
          prefs.edit().remove("trashRequest").commit();
          runOnUiThread(() -> show("report"));
        });
  }

  private void settings() {
    label("Compact 1.0.3 · offline · no accounts · no advertising");
    Spinner defaultMode =
        spinner(
            new String[] {"Default: Smart", "Default: Lossless photos", "Default: Max saving"},
            prefs.getInt("mode", 0));
    Spinner defaultPhoto =
        spinner(
            new String[] {"Default: HEIC with JPEG fallback", "Default: JPEG"},
            prefs.getBoolean("heic", true) ? 0 : 1);
    Spinner defaultAfter =
        spinner(
            new String[] {"Default: review then request Trash", "Default: keep originals"},
            prefs.getBoolean("trash", true) ? 0 : 1);
    Ui.add(body, defaultMode);
    Ui.add(body, defaultPhoto);
    Ui.add(body, defaultAfter);
    action(
        "Save defaults",
        true,
        () -> {
          prefs
              .edit()
              .putInt("mode", defaultMode.getSelectedItemPosition())
              .putBoolean("heic", defaultPhoto.getSelectedItemPosition() == 0)
              .putBoolean("trash", defaultAfter.getSelectedItemPosition() == 0)
              .apply();
          message("Defaults saved.");
        });
    Switch folders = new Switch(this);
    folders.setTextColor(Ui.TEXT);
    folders.setText("Include other media folders");
    folders.setChecked(prefs.getBoolean("allFolders", false));
    folders.setOnCheckedChangeListener(
        (b, on) -> prefs.edit().putBoolean("allFolders", on).apply());
    Ui.add(body, folders);
    label(
        "Smart: full resolution HEIC/JPEG photos and H.265 videos. Photos require mean SSIM ≥ 0.99"
            + " and minimum tile ≥ 0.98. Video checks five sampled frames at SSIM ≥ 0.98. Pixels"
            + " change; inspect your own results.\n\n"
            + "Lossless: JPEG Huffman optimization with every decoded pixel compared before saving."
            + " Videos are skipped.\n\n"
            + "Max saving: photo SSIM ≥ 0.97 and video ≥ 0.95. Fine detail may change.\n\n"
            + "Processing pauses below 20% battery when unplugged, at severe thermal status, or"
            + " when storage is low. Android may limit a background run to six hours; Resume"
            + " continues the queue.\n\n"
            + "HDR, unsupported audio/metadata, motion photos, wide-gamut images, and files with"
            + " insufficient savings are skipped.\n\n"
            + "Cloud services are not contacted by Compact. Your Gallery or backup app may"
            + " independently sync local changes.");
    action("Media permissions", false, this::requestAccess);
    action(
        "Open Android app settings",
        false,
        () ->
            startActivity(
                new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
    action(
        "Third-party license",
        false,
        () -> {
          try (java.io.InputStream in = getAssets().open("heifwriter-LICENSE.txt")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            new AlertDialog.Builder(this)
                .setTitle("AndroidX HeifWriter · Apache 2.0")
                .setMessage(out.toString("UTF-8"))
                .setPositiveButton("Close", null)
                .show();
          } catch (Exception e) {
            message(
                "AndroidX HeifWriter 1.1.0 · Apache License 2.0. See THIRD_PARTY.md in the"
                    + " source.");
          }
        });
  }

  private interface Work {
    void run() throws Exception;
  }

  private void refreshEstimates() {
    long[] source = new long[2], output = new long[2];
    int mode = prefs.getInt("mode", 0);
    boolean heic = prefs.getBoolean("heic", true);
    for (JobQueue.Job j : db.list())
      if (j.created >= SESSION_START
          && j.mode == mode
          && j.heic == heic
          && j.outputSize > 0
          && (j.state == JobState.DONE
              || j.state == JobState.PUBLISHED
              || j.state == JobState.ORIGINAL_TRASHED)) {
        int i = j.item.video ? 1 : 0;
        source[i] += j.item.size;
        output[i] += j.outputSize;
      }
    photoRatio = source[0] > 0 ? output[0] / (double) source[0] : -1;
    videoRatio = source[1] > 0 ? output[1] / (double) source[1] : -1;
  }

  private long estimate(MediaItem m) {
    int mode = prefs.getInt("mode", 0);
    if (mode == 1 && m.video) return m.size;
    double ratio = m.video ? videoRatio : photoRatio;
    return ratio > 0
        ? Math.min(m.size, (long) (m.size * ratio))
        : m.estimate(mode, prefs.getBoolean("heic", true));
  }

  private void task(String title, Work work) {
    if (busy) return;
    busy = true;
    ProgressDialog dialog = new ProgressDialog(this);
    pendingDialog = dialog;
    dialog.setMessage(title);
    dialog.setCancelable(false);
    dialog.show();
    io.execute(
        () -> {
          try {
            work.run();
          } catch (Exception e) {
            runOnUiThread(
                () ->
                    message(
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
          } finally {
            runOnUiThread(
                () -> {
                  busy = false;
                  if (!isDestroyed()) dialog.dismiss();
                });
          }
        });
  }

  private void message(String m) {
    if (!isFinishing() && !isDestroyed())
      new AlertDialog.Builder(this)
          .setTitle("Compact")
          .setMessage(m)
          .setPositiveButton("OK", null)
          .show();
  }
}
