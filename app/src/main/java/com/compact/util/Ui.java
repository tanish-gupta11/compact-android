package com.compact.util;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.Locale;

public final class Ui {
  public static final int BG = 0xff0e1015,
      SURFACE = 0xff181b23,
      ACCENT = 0xff7c9cff,
      TEXT = 0xffeceff6,
      MUTED = 0xff98a1b3,
      GREEN = 0xff86ddbb;

  public static int dp(Context c, float n) {
    return Math.round(c.getResources().getDisplayMetrics().density * n);
  }

  public static TextView text(Context c, String s, int sp, int color) {
    TextView v = new TextView(c);
    v.setText(s);
    v.setTextSize(sp);
    v.setTextColor(color);
    v.setPadding(0, dp(c, 5), 0, dp(c, 5));
    return v;
  }

  public static GradientDrawable shape(Context c, int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    return d;
  }

  public static Button button(Context c, String s, boolean primary) {
    Button b = new Button(c);
    b.setText(s);
    b.setAllCaps(false);
    b.setTextSize(15);
    b.setTextColor(primary ? BG : TEXT);
    b.setMinHeight(dp(c, 48));
    b.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x33ffffff), shape(c, primary ? ACCENT : 0xff222633, 14), null));
    b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
    return b;
  }

  public static LinearLayout column(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(1);
    return l;
  }

  public static void add(LinearLayout l, View v) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(l.getContext(), 12);
    l.addView(v, p);
  }

  public static LinearLayout card(Context c) {
    LinearLayout l = column(c);
    int p = dp(c, 18);
    l.setPadding(p, p, p, p);
    l.setBackground(shape(c, SURFACE, 20));
    return l;
  }

  public static void edge(Activity a, View root) {
    edge(a, root, 0);
  }

  /** Edge-to-edge with system-bar insets added to a base padding of {@code padDp}. */
  public static void edge(Activity a, View root, int padDp) {
    int base = dp(a, padDp);
    a.getWindow().setDecorFitsSystemWindows(false);
    root.setOnApplyWindowInsetsListener(
        (v, in) -> {
          Insets b =
              in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
          v.setPadding(b.left + base, b.top + base, b.right + base, b.bottom + base);
          return in;
        });
  }

  public static String size(long n) {
    if (n < 1024) return n + " B";
    double v = n;
    String[] u = {"KB", "MB", "GB", "TB"};
    int i = -1;
    while (v >= 1024 && i < 3) {
      v /= 1024;
      i++;
    }
    return String.format(Locale.US, "%.1f %s", v, u[i]);
  }

  public static String score(double q) {
    return String.format(Locale.US, "%.5f", q);
  }
}
