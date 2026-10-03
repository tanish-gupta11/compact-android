package com.compact.quality;

/**
 * Pass/fail rules for "looks the same". PSNR above ~40 dB is generally indistinguishable from the
 * source; SSIM guards against structural damage (blur, blocking) that PSNR averages away. Mode 0 is
 * Smart, 2 is Max saving. Lossless (mode 1) requires identical pixels instead.
 */
public final class QualityGate {
  private QualityGate() {}

  public static double minPsnr(int mode) {
    return mode == 2 ? 36 : 40;
  }

  public static double minSsim(int mode) {
    return mode == 2 ? .94 : .97;
  }

  /** Floor for the worst tile (photos) or worst sampled frame (videos). */
  public static double minLocalSsim(int mode) {
    return mode == 2 ? .91 : .95;
  }

  public static boolean pass(int mode, double ssim, double worstSsim, double psnr) {
    return psnr >= minPsnr(mode) && ssim >= minSsim(mode) && worstSsim >= minLocalSsim(mode);
  }

  /**
   * Video frames carry camera grain the encoder smooths slightly, which lowers SSIM without a visible
   * change, so the mean SSIM floor is a little lower; PSNR and the worst-frame floor stay the same.
   */
  public static boolean passVideo(int mode, double ssim, double worstSsim, double psnr) {
    return psnr >= minPsnr(mode) && ssim >= (mode == 2 ? .93 : .96) && worstSsim >= minLocalSsim(mode);
  }

  public static String describe(double ssim, double worstSsim, double psnr) {
    return String.format(java.util.Locale.US, "SSIM %.4f (worst %.4f) · PSNR %.1f dB", ssim, worstSsim, psnr);
  }
}
