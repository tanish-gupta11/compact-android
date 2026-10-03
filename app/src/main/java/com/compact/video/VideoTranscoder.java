package com.compact.video;

import android.graphics.Bitmap;
import android.media.*;
import android.view.Surface;
import com.compact.quality.Ssim;
import java.io.*;
import java.nio.*;
import java.util.*;

public final class VideoTranscoder {
  public interface Control {
    void check() throws Exception;

    void progress(double fraction);
  }

  public static final class Result {
    public File file;
    public double score;
  }

  /** A copy that did not look the same as the original; carries its scores for the retry logic. */
  static final class QualityFailure extends IOException {
    final double psnr, worst;

    QualityFailure(String message, double psnr, double worst) {
      super(message);
      this.psnr = psnr;
      this.worst = worst;
    }
  }

  public static Result compress(File source, File dir, int mode, Control control) throws Exception {
    VideoProbe p = VideoProbe.read(source);
    if (mode == 1) throw new IOException("Lossless mode keeps videos unchanged");
    String encoderProblem = encoderProblem(p);
    if (encoderProblem != null) throw new IOException(encoderProblem);
    // Ladder: constant quality first when the chip supports it, then rising bits-per-pixel budgets.
    // Busy, fast-moving footage needs more bits; calm footage passes early and saves the most.
    double[] bpp = mode == 2 ? new double[] {.065, .065, .10, .14, .20, .27} : new double[] {.10, .10, .14, .20, .27};
    Exception last = null;
    boolean usedCq = false;
    for (int attempt = 0; attempt < bpp.length; attempt++) {
      // Without constant-quality support, the first two attempts would be identical VBR encodes.
      if (attempt == 1 && !usedCq) continue;
      File out = new File(dir, "video" + attempt + ".mp4");
      long rate = Math.round(p.width * (double) p.height * p.fps * bpp[attempt]);
      // A VBR target is only a budget, not the resulting file size. The hardware encoder can
      // undershoot it substantially, so let verification enforce the actual 15% saving floor.
      try {
        usedCq = encode(source, out, p, (int) Math.min(Integer.MAX_VALUE, rate), attempt == 0, mode, control);
        Mp4TimePatcher.copy(source, out);
        double score = verify(source, out, p, mode, control);
        Result r = new Result();
        r.file = out;
        r.score = score;
        return r;
      } catch (QualityFailure q) {
        last = q;
        com.compact.util.Files.discard(out);
        control.check();
        // A plateau in aggregate PSNR does not prove that the worst frame cannot improve.
      } catch (Exception e) {
        last = e;
        com.compact.util.Files.discard(out);
        control.check();
      }
    }
    if (last instanceof QualityFailure)
      throw new IOException(mode == 2
          ? "Max could not pass its quality check at a useful size. " + last.getMessage()
              + ". Original kept."
          : "Smart could not preserve enough detail at a useful size. " + last.getMessage()
              + ". Original kept. Max may save space with visible softening; try it only on a copy.");
    throw last;
  }

  /** Null when this phone has a hardware HEVC encoder for the video's size and frame rate. */
  static String encoderProblem(VideoProbe p) {
    boolean any = false;
    for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
      if (!info.isEncoder() || !info.isHardwareAccelerated()) continue;
      for (String type : info.getSupportedTypes()) {
        if (!type.equals("video/hevc")) continue;
        any = true;
        MediaCodecInfo.VideoCapabilities v = info.getCapabilitiesForType(type).getVideoCapabilities();
        if (v != null && v.areSizeAndRateSupported(p.width, p.height, Math.max(1, p.fps))) return null;
      }
    }
    return any
        ? String.format(java.util.Locale.US,
            "This phone's video encoder can't handle %dx%d at %.0f fps; original kept",
            p.width, p.height, p.fps)
        : "This phone has no hardware H.265 encoder; original kept";
  }

  /** Returns true when the encoder ran in constant-quality mode. */
  private static boolean encode(
      File src, File dst, VideoProbe p, int rate, boolean tryCq, int mode, Control control)
      throws Exception {
    return encode(src, dst, p, rate, tryCq, mode, control, null);
  }

  /** Preferred codec is used by device diagnostics to compare the phone's hardware encoders. */
  private static boolean encode(
      File src, File dst, VideoProbe p, int rate, boolean tryCq, int mode, Control control,
      String preferredCodec) throws Exception {
    MediaCodec encoder = null, decoder = null;
    Surface surface = null;
    MediaMuxer muxer = null;
    MediaExtractor input = new MediaExtractor(), audio = new MediaExtractor();
    boolean muxStarted = false;
    try {
      MediaFormat format = MediaFormat.createVideoFormat("video/hevc", p.width, p.height);
      format.setInteger(
          MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
      format.setInteger(MediaFormat.KEY_BIT_RATE, rate);
      format.setFloat(MediaFormat.KEY_FRAME_RATE, p.fps);
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
      format.setInteger(
          MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
      format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
      for (String key :
          new String[] {
            MediaFormat.KEY_COLOR_STANDARD,
            MediaFormat.KEY_COLOR_RANGE,
            MediaFormat.KEY_COLOR_TRANSFER
          }) if (p.format.containsKey(key)) format.setInteger(key, p.format.getInteger(key));
      String name = null;
      // The platform lists its preferred codec first (Codec2 before legacy OMX); take the first match.
      for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos())
        if (name == null && info.isEncoder() && info.isHardwareAccelerated()
            && (preferredCodec == null || preferredCodec.equals(info.getName())))
          for (String type : info.getSupportedTypes())
            if (type.equals("video/hevc")) {
              MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
              MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
              if (vc != null
                  && vc.areSizeAndRateSupported(p.width, p.height, Math.max(1, p.fps))
                  && caps.isFormatSupported(format)
                  && caps.getEncoderCapabilities()
                      .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                name = info.getName();
            }
      if (name == null) throw new IOException("No compatible hardware HEVC encoder");
      encoder = MediaCodec.createByCodecName(name);
      MediaCodecInfo.EncoderCapabilities ec =
          encoder.getCodecInfo().getCapabilitiesForType("video/hevc").getEncoderCapabilities();
      boolean cq = tryCq && ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
      if (cq) {
        android.util.Range<Integer> range = ec.getQualityRange();
        format.setInteger(
            MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
        format.setInteger(
            MediaFormat.KEY_QUALITY,
            range.getLower()
                + (int) ((range.getUpper() - range.getLower()) * (mode == 2 ? .70 : .85)));
      }
      encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      surface = encoder.createInputSurface();
      encoder.start();
      input.setDataSource(src.getPath());
      input.selectTrack(p.track);
      MediaFormat decode = input.getTrackFormat(p.track);
      decode.setInteger(MediaFormat.KEY_ROTATION, 0);
      decoder = MediaCodec.createDecoderByType(p.mime);
      decoder.configure(decode, surface, null, 0);
      decoder.start();
      muxer = new MediaMuxer(dst.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      muxer.setOrientationHint(p.rotation);
      float[] loc = Location.parse(p.location);
      if (loc != null) muxer.setLocation(loc[0], loc[1]);
      audio.setDataSource(src.getPath());
      Map<Integer, Integer> audioMap = new HashMap<>();
      for (int t : p.audio) {
        audioMap.put(t, muxer.addTrack(audio.getTrackFormat(t)));
        audio.selectTrack(t);
      }
      ByteBuffer audioBuffer = ByteBuffer.allocate(4 * 1024 * 1024);
      MediaCodec.BufferInfo ei = new MediaCodec.BufferInfo(), di = new MediaCodec.BufferInfo();
      boolean inputDone = false, decodeDone = false, encodeDone = false;
      int videoTrack = -1;
      long activity = android.os.SystemClock.elapsedRealtime();
      while (!encodeDone) {
        control.check();
        boolean moved = false;
        // Drain encoder before rendering more decoded buffers to avoid surface backpressure.
        int index = encoder.dequeueOutputBuffer(ei, 1000);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          if (muxStarted) throw new IOException("Encoder changed format twice");
          videoTrack = muxer.addTrack(encoder.getOutputFormat());
          muxer.start();
          muxStarted = true;
          moved = true;
        } else if (index >= 0) {
          ByteBuffer buffer = encoder.getOutputBuffer(index);
          if ((ei.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) ei.size = 0;
          if (ei.size > 0) {
            if (!muxStarted || buffer == null) throw new IOException("Missing encoder format");
            copyAudioUntil(audio, muxer, audioMap, audioBuffer, ei.presentationTimeUs);
            buffer.position(ei.offset);
            buffer.limit(ei.offset + ei.size);
            muxer.writeSampleData(videoTrack, buffer, ei);
            control.progress(Math.min(1, ei.presentationTimeUs / (double) p.duration));
          }
          encodeDone = (ei.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
          encoder.releaseOutputBuffer(index, false);
          moved = true;
        }
        if (!decodeDone) {
          index = decoder.dequeueOutputBuffer(di, 1000);
          if (index >= 0) {
            boolean render = di.size > 0;
            if (render) decoder.releaseOutputBuffer(index, di.presentationTimeUs * 1000);
            else decoder.releaseOutputBuffer(index, false);
            if ((di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              decodeDone = true;
              encoder.signalEndOfInputStream();
            }
            moved = true;
          }
        }
        if (!inputDone) {
          index = decoder.dequeueInputBuffer(1000);
          if (index >= 0) {
            ByteBuffer b = decoder.getInputBuffer(index);
            b.clear();
            int n = input.readSampleData(b, 0);
            if (n < 0) {
              decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputDone = true;
            } else {
              if ((input.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                throw new IOException("Encrypted media");
              decoder.queueInputBuffer(index, 0, n, input.getSampleTime(), 0);
              input.advance();
            }
            moved = true;
          }
        }
        if (moved) activity = android.os.SystemClock.elapsedRealtime();
        else if (android.os.SystemClock.elapsedRealtime() - activity > 60000)
          throw new IOException("Hardware encoder stalled");
      }
      copyAudioUntil(audio, muxer, audioMap, audioBuffer, Long.MAX_VALUE);
      muxer.stop();
      muxStarted = false;
      return cq;
    } finally {
      input.release();
      audio.release();
      if (decoder != null) {
        try {
          decoder.stop();
        } catch (Exception ignored) {
        }
        decoder.release();
      }
      if (encoder != null) {
        try {
          encoder.stop();
        } catch (Exception ignored) {
        }
        encoder.release();
      }
      if (surface != null) surface.release();
      if (muxer != null) {
        if (muxStarted)
          try {
            muxer.stop();
          } catch (Exception ignored) {
          }
        muxer.release();
      }
    }
  }

  private static void copyAudioUntil(
      MediaExtractor e, MediaMuxer mux, Map<Integer, Integer> tracks, ByteBuffer b, long until)
      throws IOException {
    while (e.getSampleTrackIndex() >= 0 && e.getSampleTime() <= until) {
      b.clear();
      int n = e.readSampleData(b, 0);
      if (n < 0) break;
      if ((e.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
        throw new IOException("Encrypted audio");
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      info.set(0, n, e.getSampleTime(), e.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC);
      b.position(0);
      b.limit(n);
      mux.writeSampleData(tracks.get(e.getSampleTrackIndex()), b, info);
      e.advance();
    }
  }

  private static double verify(File src, File dst, VideoProbe a, int mode, Control control)
      throws Exception {
    VideoProbe b = VideoProbe.read(dst);
    if (a.width != b.width
        || a.height != b.height
        || a.rotation != b.rotation
        || Math.abs(a.duration - b.duration) > 150000
        || a.audio.size() != b.audio.size()) throw new IOException("Video structure mismatch");
    // Colour tags may legitimately change (e.g. full -> limited range, converted by the pipeline);
    // HDR is refused up front and the frame comparison below catches any visible colour shift.
    float[] al = Location.parse(a.location), bl = Location.parse(b.location);
    if (al != null
        && (bl == null || Math.abs(al[0] - bl[0]) > .0001 || Math.abs(al[1] - bl[1]) > .0001))
      throw new IOException("Video GPS mismatch");
    for (int i = 0; i < a.audio.size(); i++)
      if (!VideoProbe.audioDigest(src, a.audio.get(i))
          .equals(VideoProbe.audioDigest(dst, b.audio.get(i))))
        throw new IOException("Audio samples or timestamps changed");
    VideoProbe.verifyTimestamps(src, a.track, dst, b.track);
    double min = 1, sum = 0, squaredError = 0;
    long pixels = 0;
    int samples = 0;
    long frame = (long) (1_000_000 / Math.max(1, a.fps));
    MediaMetadataRetriever x = new MediaMetadataRetriever(), y = new MediaMetadataRetriever();
    try {
      x.setDataSource(src.getPath());
      y.setDataSource(dst.getPath());
      int w = a.width, h = a.height;
      if (a.rotation == 90 || a.rotation == 270) {
        w = a.height;
        h = a.width;
      }
      // Compare at up to 1920 px so 1080p is checked at full resolution (smaller previews hide blur).
      double scale = Math.min(1, 1920d / Math.max(w, h));
      w = Math.max(8, (int) (w * scale));
      h = Math.max(8, (int) (h * scale));
      for (double t : new double[] {.1, .3, .5, .7, .9}) {
        control.check();
        long at = (long) (a.duration * t);
        Bitmap bb = y.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST, w, h);
        if (bb == null) throw new IOException("Video frame decode failed");
        Ssim.Result best = null;
        try {
          int ww = bb.getWidth(), hh = bb.getHeight();
          int[] bp = new int[ww * hh], ap = new int[bp.length];
          bb.getPixels(bp, 0, ww, 0, 0, ww, hh);
          // Seeking can land one frame apart between two files; take the best-aligned neighbour.
          for (long offset : new long[] {0, -frame, frame}) {
            Bitmap aa = x.getScaledFrameAtTime(Math.max(0, at + offset), MediaMetadataRetriever.OPTION_CLOSEST, w, h);
            if (aa == null) continue;
            try {
              if (aa.getWidth() != ww || aa.getHeight() != hh) throw new IOException("Frame dimensions differ");
              aa.getPixels(ap, 0, ww, 0, 0, ww, hh);
              Ssim.Result r = Ssim.measure(ap, bp, ww, hh);
              if (best == null || r.ssim > best.ssim) best = r;
            } finally {
              aa.recycle();
            }
            if (best != null && best.ssim >= .97) break;
          }
        } finally {
          bb.recycle();
        }
        if (best == null) throw new IOException("Video frame decode failed");
        min = Math.min(min, best.ssim);
        sum += best.ssim;
        squaredError += best.squaredError;
        pixels += best.pixels;
        samples++;
      }
    } finally {
      x.release();
      y.release();
    }
    double mean = sum / samples, psnr = Ssim.psnr(squaredError, pixels);
    if (!com.compact.quality.QualityGate.passVideo(mode, mean, min, psnr))
      throw new QualityFailure("Quality check failed: " + com.compact.quality.QualityGate.describe(mean, min, psnr), psnr, min);
    if (dst.length() > src.length() * .85)
      throw new IOException("Verified video saves less than 15%");
    return mean;
  }
}
