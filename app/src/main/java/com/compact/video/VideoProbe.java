package com.compact.video;

import android.media.*;
import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;

public final class VideoProbe {
  public MediaFormat format;
  public int track = -1, width, height, rotation;
  public float fps;
  public long duration, bitrate;
  public String mime, location;
  public final List<Integer> audio = new ArrayList<>();

  public static VideoProbe read(File file) throws Exception {
    VideoProbe p = new VideoProbe();
    MediaExtractor e = new MediaExtractor();
    MediaMetadataRetriever r = new MediaMetadataRetriever();
    try {
      e.setDataSource(file.getPath());
      Map<java.util.UUID, byte[]> drm = e.getPsshInfo();
      if (drm != null && !drm.isEmpty()) throw new IOException("DRM media unsupported");
      for (int i = 0; i < e.getTrackCount(); i++) {
        MediaFormat f = e.getTrackFormat(i);
        String m = f.getString(MediaFormat.KEY_MIME);
        if (m.startsWith("video/")) {
          if (p.track >= 0) throw new IOException("Multiple video tracks unsupported");
          p.track = i;
          p.format = f;
          p.mime = m;
        } else if (m.startsWith("audio/")) {
          if (!m.equals("audio/mp4a-latm") && !m.equals("audio/3gpp") && !m.equals("audio/amr-wb"))
            throw new IOException("Audio format cannot be copied to MP4");
          p.audio.add(i);
        } else throw new IOException("Additional timed metadata track preserved by skipping");
      }
      if (p.track < 0) throw new IOException("No video track");
      MediaFormat f = p.format;
      p.width = f.getInteger(MediaFormat.KEY_WIDTH);
      p.height = f.getInteger(MediaFormat.KEY_HEIGHT);
      p.duration = f.getLong(MediaFormat.KEY_DURATION);
      r.setDataSource(file.getPath());
      p.rotation = integer(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
      p.location = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
      p.bitrate = longNumber(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
      if (p.bitrate <= 0) p.bitrate = (long) (file.length() * 8e6 / p.duration);
      if (f.containsKey(MediaFormat.KEY_FRAME_RATE))
        p.fps = f.getNumber(MediaFormat.KEY_FRAME_RATE).floatValue();
      if (p.fps <= 0) {
        e.selectTrack(p.track);
        long first = e.getSampleTime(), last = first;
        int count = 0;
        while (count < 60 && e.advance()) {
          last = e.getSampleTime();
          count++;
        }
        p.fps = count > 0 && last > first ? (float) (count * 1e6 / (last - first)) : 30;
      }
      if (p.duration < 2_000_000) throw new IOException("Video shorter than two seconds");
      int transfer =
          f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)
              ? f.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
              : 0;
      int profile =
          f.containsKey(MediaFormat.KEY_PROFILE) ? f.getInteger(MediaFormat.KEY_PROFILE) : 0;
      if (!p.mime.equals("video/avc") && !p.mime.equals("video/hevc"))
        throw new IOException("Only AVC/HEVC camera video is supported");
      if (p.mime.equals("video/avc")
          && (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
              || profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422
              || profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444))
        throw new IOException("High-bit-depth / non-4:2:0 AVC preserved unchanged");
      if (transfer == 6
          || transfer == 7
          || f.containsKey("hdr-static-info")
          || p.mime.equals("video/hevc") && profile != 0 && profile != 1)
        throw new IOException("HDR / Main10 video preserved unchanged");
      return p;
    } finally {
      e.release();
      r.release();
    }
  }

  private static int integer(String s) {
    return s == null ? 0 : Integer.parseInt(s);
  }

  private static long longNumber(String s) {
    return s == null ? 0 : Long.parseLong(s);
  }

  public static String audioDigest(File file, int track) throws Exception {
    MediaExtractor e = new MediaExtractor();
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    try {
      e.setDataSource(file.getPath());
      e.selectTrack(track);
      MediaFormat f = e.getTrackFormat(track);
      hash.update(f.getString(MediaFormat.KEY_MIME).getBytes("UTF-8"));
      for (String k : new String[] {MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT})
        hash.update(ByteBuffer.allocate(4).putInt(f.getInteger(k)).array());
      for (int i = 0; f.containsKey("csd-" + i); i++)
        hash.update(f.getByteBuffer("csd-" + i).duplicate());
      ByteBuffer buffer = ByteBuffer.allocate(4 * 1024 * 1024);
      while (e.getSampleTime() >= 0) {
        buffer.clear();
        int n = e.readSampleData(buffer, 0);
        if (n < 0) break;
        hash.update(ByteBuffer.allocate(12).putLong(e.getSampleTime()).putInt(n).array());
        hash.update(buffer.array(), 0, n);
        e.advance();
      }
      return Arrays.toString(hash.digest());
    } finally {
      e.release();
    }
  }

  public static void verifyTimestamps(File a, int at, File b, int bt) throws Exception {
    MediaExtractor x = new MediaExtractor(), y = new MediaExtractor();
    try {
      x.setDataSource(a.getPath());
      y.setDataSource(b.getPath());
      x.selectTrack(at);
      y.selectTrack(bt);
      ArrayList<Long> xp = new ArrayList<>(), yp = new ArrayList<>();
      while (x.getSampleTime() >= 0) {
        xp.add(x.getSampleTime());
        x.advance();
      }
      while (y.getSampleTime() >= 0) {
        yp.add(y.getSampleTime());
        y.advance();
      }
      if (xp.size() != yp.size()) throw new IOException("Video frame count changed");
      Collections.sort(xp);
      Collections.sort(yp);
      for (int i = 0; i < xp.size(); i++)
        if (Math.abs(xp.get(i) - yp.get(i)) > 2000)
          throw new IOException("Video frame timing changed");
    } finally {
      x.release();
      y.release();
    }
  }
}
