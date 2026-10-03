package com.compact.video;

import java.io.*;
import java.util.*;

/** Strict ISO-BMFF walker. Never scans raw mdat payload for apparent box names. */
public final class Mp4TimePatcher {
  private Mp4TimePatcher() {}

  public static long[] read(File file) throws IOException {
    try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
      List<long[]> boxes = new ArrayList<>();
      walk(f, 0, f.length(), boxes, 0);
      for (long[] box : boxes) {
        if (box[1] == 0x6d766864) return times(f, box[0]);
      }
    }
    throw new IOException("MP4 movie time missing");
  }

  public static void copy(File source, File output) throws IOException {
    long[] times = read(source);
    try (RandomAccessFile f = new RandomAccessFile(output, "rw")) {
      List<long[]> boxes = new ArrayList<>();
      walk(f, 0, f.length(), boxes, 0);
      if (boxes.isEmpty()) throw new IOException("MP4 timing boxes missing");
      for (long[] box : boxes) {
        long p = box[0];
        f.seek(p);
        int v = f.readUnsignedByte();
        f.skipBytes(3);
        if (v == 1) {
          f.writeLong(times[0]);
          f.writeLong(times[1]);
        } else if (v == 0 && times[0] <= 0xffffffffL && times[1] <= 0xffffffffL) {
          f.writeInt((int) times[0]);
          f.writeInt((int) times[1]);
        } else throw new IOException("Unsupported MP4 time version/range");
      }
      f.getFD().sync();
    }
    if (!Arrays.equals(times, read(output))) throw new IOException("MP4 time verification failed");
  }

  private static long[] times(RandomAccessFile f, long p) throws IOException {
    f.seek(p);
    int v = f.readUnsignedByte();
    f.skipBytes(3);
    if (v == 0) return new long[] {f.readInt() & 0xffffffffL, f.readInt() & 0xffffffffL};
    if (v == 1) {
      long a = f.readLong(), b = f.readLong();
      if (a < 0 || b < 0) throw new IOException("Unsigned time overflow");
      return new long[] {a, b};
    }
    throw new IOException("Unknown time version");
  }

  private static void walk(RandomAccessFile f, long begin, long end, List<long[]> times, int depth)
      throws IOException {
    if (depth > 8) throw new IOException("MP4 nesting too deep");
    for (long p = begin; p < end; ) {
      if (end - p < 8) throw new IOException("Truncated MP4 box");
      f.seek(p);
      long size = f.readInt() & 0xffffffffL;
      int type = f.readInt();
      long header = 8;
      if (size == 1) {
        size = f.readLong();
        header = 16;
      } else if (size == 0) size = end - p;
      if (size < header || size > end - p) throw new IOException("Invalid MP4 box size");
      if (type == 0x6d6f6f76 || type == 0x7472616b || type == 0x6d646961)
        walk(f, p + header, p + size, times, depth + 1);
      if (type == 0x6d766864 || type == 0x746b6864 || type == 0x6d646864) {
        f.seek(p + header);
        int v = f.readUnsignedByte();
        if (size - header < (v == 1 ? 20 : 12)) throw new IOException("Truncated time box");
        times.add(new long[] {p + header, type});
      }
      p += size;
    }
  }
}
