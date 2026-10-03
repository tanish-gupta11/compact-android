package com.compact.util;

import android.content.*;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
import java.security.*;

public final class Files {
  public static String copy(InputStream in, File out) throws Exception {
    MessageDigest d = MessageDigest.getInstance("SHA-256");
    try (InputStream input = in;
        FileOutputStream o = new FileOutputStream(out)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = input.read(b)) != -1) {
        o.write(b, 0, n);
        d.update(b, 0, n);
      }
      o.getFD().sync();
    }
    return hex(d.digest());
  }

  public static String hash(InputStream in) throws Exception {
    MessageDigest d = MessageDigest.getInstance("SHA-256");
    try (InputStream input = in) {
      byte[] b = new byte[65536];
      int n;
      while ((n = input.read(b)) != -1) d.update(b, 0, n);
    }
    return hex(d.digest());
  }

  public static String hash(File f) throws Exception {
    return hash(new FileInputStream(f));
  }

  public static InputStream original(Context c, Uri uri) throws IOException {
    InputStream in = c.getContentResolver().openInputStream(MediaStore.setRequireOriginal(uri));
    if (in == null) throw new IOException("Original unavailable");
    return in;
  }

  private static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte v : b) s.append(String.format(java.util.Locale.US, "%02x", v & 255));
    return s.toString();
  }

  public static byte[] read(File f) throws IOException {
    if (f.length() > 64 * 1024 * 1024)
      throw new IOException("Photo too large for this phone's memory");
    try (InputStream i = new FileInputStream(f);
        ByteArrayOutputStream o = new ByteArrayOutputStream()) {
      byte[] b = new byte[65536];
      int n;
      while ((n = i.read(b)) != -1) o.write(b, 0, n);
      return o.toByteArray();
    }
  }

  public static void write(File f, byte[] b) throws IOException {
    try (FileOutputStream o = new FileOutputStream(f)) {
      o.write(b);
      o.getFD().sync();
    }
  }

  public static void discard(File f) {
    if (f.exists() && !f.delete())
      android.util.Log.w("Compact", "Could not remove private temporary file");
  }
}
