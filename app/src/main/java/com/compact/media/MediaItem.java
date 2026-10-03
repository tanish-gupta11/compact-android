package com.compact.media;

import android.net.Uri;

public final class MediaItem {
  public Uri uri;
  public String name, path, mime;
  public long size, dateTaken, dateModified, duration;
  public int width, height, orientation;
  public boolean video;
  public float fps = 30;
  public long audioBitrate = 128000;

  public String base() {
    int p = name.lastIndexOf('.');
    return p > 0 ? name.substring(0, p) : name;
  }

  public long estimate(int mode, boolean heic) {
    if (mode == 1) return video ? size : (long) (size * .92);
    if (!video) return (long) (size * (heic ? .5 : .7));
    return Math.min(
        size,
        com.compact.video.BitrateCalculator.estimatedBytes(
            duration * 1000,
            mode == 2
                ? com.compact.video.BitrateCalculator.maxSavingBitsPerSecond(width, height, fps)
                : com.compact.video.BitrateCalculator.smartBitsPerSecond(width, height, fps),
            audioBitrate));
  }
}
