package com.compact.video;

/** Deterministic bitrate estimates used by review UI and later encoder selection. */
public final class BitrateCalculator {
  private BitrateCalculator() {}

  public static long smartBitsPerSecond(int width, int height, float fps) {
    return Math.max(250_000L, Math.round(width * (double) height * Math.max(1, fps) * 0.10));
  }

  public static long maxSavingBitsPerSecond(int width, int height, float fps) {
    return Math.max(180_000L, Math.round(width * (double) height * Math.max(1, fps) * 0.065));
  }

  public static long estimatedBytes(long durationUs, long videoBits, long audioBitsPerSecond) {
    return Math.max(
        1,
        Math.round((durationUs / 1_000_000d) * (videoBits + Math.max(0, audioBitsPerSecond)) / 8d));
  }
}
