package pl.viamio.serwis;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Książka serwisowa maszyn – aplikacja wczytuje stronę z plików w telefonie,
 * więc działa bez internetu. Dane (localStorage) zostają w telefonie.
 */
public class MainActivity extends Activity {
    private static final String RELEASES = "https://api.github.com/repos/Viamio/Serwis-maszyn/releases/latest";
    private WebView web;
    private long downloadId = -1;
    private BroadcastReceiver downloadDone;

    private long versionCode() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    private void js(String fn, String arg) {
        runOnUiThread(() -> web.evaluateJavascript("window." + fn + " && window." + fn + "(" + arg + ")", null));
    }

    private void checkUpdate() {
        new Thread(() -> {
            JSONObject out = new JSONObject();
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "SerwisMaszyn");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject rel = new JSONObject(sb.toString());
                String tag = rel.optString("tag_name", "");
                long code = Long.parseLong(tag.replaceAll("[^0-9]", ""));
                String url = "";
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break; }
                }
                out.put("code", code);
                out.put("url", url);
            } catch (Exception e) {
                try { out.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
            }
            js("onUpdateInfo", JSONObject.quote(out.toString()));
        }).start();
    }

    private void downloadUpdate(String url) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            File old = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SerwisMaszyn.apk");
            if (old.exists()) old.delete();
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                    .setTitle("Serwis Maszyn – aktualizacja")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "SerwisMaszyn.apk");
            if (downloadDone == null) {
                downloadDone = new BroadcastReceiver() {
                    @Override public void onReceive(Context ctx, Intent intent) {
                        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                        if (id != downloadId) return;
                        Uri apk = dm.getUriForDownloadedFile(id);
                        js("onUpdateDone", apk != null ? "true" : "false");
                        if (apk == null) return;
                        Intent install = new Intent(Intent.ACTION_VIEW);
                        install.setDataAndType(apk, "application/vnd.android.package-archive");
                        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { startActivity(install); } catch (Exception e) { openExternal(url); }
                    }
                };
                IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadDone, f, Context.RECEIVER_EXPORTED);
                else registerReceiver(downloadDone, f);
            }
            downloadId = dm.enqueue(req);
        } catch (Exception e) {
            js("onUpdateDone", "false");
            openExternal(url);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                openExternal(u.toString());
                return true;
            }
        });

        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        Reminders.schedule(this);
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) { }
    }

    class Bridge {
        @JavascriptInterface
        public void saveSchedule(String json) {
            getSharedPreferences(Reminders.PREFS, MODE_PRIVATE).edit().putString(Reminders.KEY, json).apply();
        }

        @JavascriptInterface
        public void share(String text) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "Kopia zapasowa – Serwis Maszyn");
            i.putExtra(Intent.EXTRA_TEXT, text);
            runOnUiThread(() -> startActivity(Intent.createChooser(i, "Wyślij kopię zapasową")));
        }

        @JavascriptInterface
        public long appVersion() { return versionCode(); }

        @JavascriptInterface
        public void checkUpdate() { MainActivity.this.checkUpdate(); }

        @JavascriptInterface
        public void downloadUpdate(String url) { runOnUiThread(() -> MainActivity.this.downloadUpdate(url)); }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> openExternal(url));
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }
    @Override protected void onDestroy() {
        if (downloadDone != null) try { unregisterReceiver(downloadDone); } catch (Exception ignored) { }
        super.onDestroy();
    }
    @Override protected void onPause() { web.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override public void onBackPressed() {
        web.evaluateJavascript("(window.appBack && window.appBack()) ? 'yes' : 'no'", r -> {
            if (r == null || !r.contains("yes")) finish();
        });
    }
}
