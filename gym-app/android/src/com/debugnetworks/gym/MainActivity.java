package com.debugnetworks.gym;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * PPL Coach: a workout, bodyweight and diet log. The app itself is the web page in assets/www,
 * served to a WebView from a private https address so its records stay in the app's storage.
 * This class adds what a phone app needs: the rest-timer alert (vibration, sound and a
 * notification when the app is in the background), keeping the screen on during a workout,
 * sharing the weekly report and backups, picking a backup file, and the back button.
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String START = "https://" + HOST + "/www/index.html";
    static final int PICK_FILE = 41;
    static final int ASK_NOTIFY = 42;
    static final int REST_NOTE = 7;
    static final String CHANNEL = "rest-timer";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable restAlarm;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true); // test builds only, so the emulator check can drive the page
        }
        web = new WebView(this);
        web.setBackgroundColor(0xFF111315);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "GymAndroid");
        web.setWebViewClient(new AppClient());
        web.setWebChromeClient(new AppChrome());
        web.loadUrl(START);
    }

    /** Back closes the open sheet first, then returns to Today, then leaves the app. */
    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(function(){try{return !!(window.__gymBack&&window.__gymBack());}catch(e){return false;}})()",
            new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String handled) {
                    if (!"true".equals(handled)) leaveApp();
                }
            });
    }

    void leaveApp() {
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (web != null) web.onResume();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(REST_NOTE);
    }

    @Override
    protected void onDestroy() {
        cancelRest();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILE) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[] { data.getData() };
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ---- Rest timer ----

    void startRest(long ms, final String label) {
        cancelRest();
        askNotifyOnce();
        restAlarm = new Runnable() {
            public void run() {
                restAlarm = null;
                restOver(label);
            }
        };
        handler.postDelayed(restAlarm, Math.max(0, ms));
    }

    void cancelRest() {
        if (restAlarm != null) {
            handler.removeCallbacks(restAlarm);
            restAlarm = null;
        }
    }

    void restOver(String label) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        long[] pattern = new long[] { 0, 350, 150, 350 };
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else v.vibrate(pattern, -1);
        }
        try {
            Ringtone r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            if (r != null) r.play();
        } catch (Exception e) {
            // no sound available; the vibration is enough
        }
        if (!resumed) notifyRestOver(label);
    }

    void askNotifyOnce() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences p = getSharedPreferences("app", MODE_PRIVATE);
        if (p.getBoolean("askedNotify", false)) return;
        p.edit().putBoolean("askedNotify", true).apply();
        requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, ASK_NOTIFY);
    }

    void notifyRestOver(String label) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Rest timer", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Tells you when your rest between sets is over");
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_HIGH);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Rest over")
            .setContentText(label == null || label.length() == 0 ? "Time for your next set" : "Next: " + label)
            .setAutoCancel(true)
            .setContentIntent(pi);
        nm.notify(REST_NOTE, b.build());
    }

    // ---- Sharing ----

    void shareText(String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(send, "Share"));
        } catch (ActivityNotFoundException e) {
            toast("No app on this phone can share this.");
        }
    }

    void shareFile(String base64, String name, String mime, String text) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            File dir = new File(getCacheDir(), ShareProvider.DIR);
            if (!dir.exists()) dir.mkdirs();
            String safe = (name == null || name.length() == 0) ? "file" : name.replaceAll("[^A-Za-z0-9._-]", "_");
            File file = new File(dir, safe);
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            Uri uri = new Uri.Builder().scheme("content").authority(ShareProvider.AUTHORITY).appendPath(safe).build();
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType((mime == null || mime.length() == 0) ? "application/octet-stream" : mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            if (text != null && text.length() > 0) send.putExtra(Intent.EXTRA_TEXT, text);
            send.setClipData(ClipData.newRawUri(safe, uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Save or share " + safe));
        } catch (Exception e) {
            toast("Could not share the file. Try again.");
        }
    }

    void copyText(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("PPL Coach", text));
    }

    void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            toast("No app on this phone can open this link.");
        }
    }

    static String mimeFor(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".css")) return "text/css";
        return "application/octet-stream";
    }

    /** Methods the web page calls as window.GymAndroid. They arrive on a background thread. */
    public class Bridge {
        @JavascriptInterface
        public void startRest(final double ms, final String label) {
            runOnUiThread(new Runnable() {
                public void run() { MainActivity.this.startRest((long) ms, label); }
            });
        }

        @JavascriptInterface
        public void cancelRest() {
            runOnUiThread(new Runnable() {
                public void run() { MainActivity.this.cancelRest(); }
            });
        }

        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface
        public void shareText(final String text) {
            runOnUiThread(new Runnable() {
                public void run() { MainActivity.this.shareText(text); }
            });
        }

        @JavascriptInterface
        public void shareFile(final String base64, final String name, final String mime, final String text) {
            runOnUiThread(new Runnable() {
                public void run() { MainActivity.this.shareFile(base64, name, mime, text); }
            });
        }

        @JavascriptInterface
        public void copyText(final String text) {
            runOnUiThread(new Runnable() {
                public void run() { MainActivity.this.copyText(text); }
            });
        }

        @JavascriptInterface
        public String appVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "?";
            }
        }
    }

    /** Serves the app from assets and sends any other link to the phone's browser. */
    class AppClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri uri = Uri.parse(url);
            if (HOST.equals(uri.getHost())) return false;
            openExternal(url);
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!HOST.equals(uri.getHost())) return null;
            String path = uri.getPath();
            if (path == null || path.length() <= 1) path = "/www/index.html";
            if (path.endsWith("/")) path = path + "index.html";
            String asset = path.substring(1);
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Cache-Control", "no-cache");
            try {
                InputStream in = getAssets().open(asset);
                return new WebResourceResponse(mimeFor(asset), "UTF-8", 200, "OK", headers, in);
            } catch (IOException e) {
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", headers, new ByteArrayInputStream(new byte[0]));
            }
        }
    }

    /** Opens the phone's file picker when the page asks for a backup file. */
    class AppChrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            try {
                startActivityForResult(Intent.createChooser(pick, "Choose your backup file"), PICK_FILE);
                return true;
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                toast("No file picker found on this phone.");
                return false;
            }
        }
    }
}
