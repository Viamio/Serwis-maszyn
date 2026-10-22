package pl.viamio.serwis;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Codzienne sprawdzanie terminów wymian i powiadomienie o 8:00. */
final class Reminders {
    static final String PREFS = "serwis";
    static final String KEY = "schedule";
    private static final String CHANNEL = "wymiany";

    private Reminders() { }

    static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 8);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0,
                new Intent(ctx, ReminderReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pi);
    }

    static void check(Context ctx) {
        String json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
        List<String> late = new ArrayList<>();
        List<String> soon = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String line = o.optString("m") + " – " + o.optString("n");
                boolean isLate = false, isSoon = false;
                if (!o.isNull("remH")) {
                    double rem = o.optDouble("remH");
                    double warn = o.optDouble("warn", 0);
                    if (rem < 0) isLate = true; else if (rem <= warn) isSoon = true;
                }
                if (!o.isNull("due")) {
                    Date due = f.parse(o.getString("due"));
                    if (due != null) {
                        long days = (due.getTime() - now) / 86400000L;
                        if (due.getTime() < now) isLate = true; else if (days <= 30) isSoon = true;
                    }
                }
                if (isLate) late.add(line); else if (isSoon) soon.add(line);
            }
        } catch (Exception e) {
            return;
        }
        if (late.isEmpty() && soon.isEmpty()) return;
        notify(ctx, late, soon);
    }

    private static void notify(Context ctx, List<String> late, List<String> soon) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Terminy wymian", NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(ctx, CHANNEL);
        } else {
            b = new Notification.Builder(ctx);
        }
        String title = !late.isEmpty()
                ? "Do wymiany: " + late.size() + (soon.isEmpty() ? "" : ", zbliża się: " + soon.size())
                : "Zbliża się wymiana: " + soon.size();
        Notification.InboxStyle style = new Notification.InboxStyle();
        for (String s : late) style.addLine("⚠ " + s);
        for (String s : soon) style.addLine("⏱ " + s);
        String first = !late.isEmpty() ? late.get(0) : soon.get(0);
        PendingIntent open = PendingIntent.getActivity(ctx, 1,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(first)
                .setStyle(style)
                .setContentIntent(open)
                .setAutoCancel(true);
        try {
            nm.notify(1, b.build());
        } catch (SecurityException ignored) { }
    }
}
