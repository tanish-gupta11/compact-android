package com.compact.work;

import android.content.*;
import android.os.*;

public final class Scheduler {
  public static String gate(Context c, boolean chargingOnly, long needed) {
    Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    if (b == null) return "Waiting for battery information";
    boolean charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
        level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
    if (chargingOnly && !charging) return "Paused · connect a charger";
    if (!charging && (scale <= 0 || level * 100 / scale < 20)) return "Paused · battery below 20%";
    PowerManager p = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
    if (p.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE)
      return "Paused · phone needs to cool down";
    if (c.getCacheDir().getUsableSpace() < needed)
      return "Paused · need " + (needed / 1048576) + " MB of free temporary storage";
    return null;
  }
}
