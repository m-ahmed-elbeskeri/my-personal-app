package app.personal.my;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

public class MainActivity extends Activity {
    /** Set while the app is on screen, so an alarm can show inside it. */
    static volatile MainActivity visible;
    static final String ROUTE = "route";

    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_LOCATION = 1, REQ_NOTIFICATIONS = 2;
    private static final int LOCK_FLAGS = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

    private WebView web;
    private boolean pageReady;
    private String pendingJs;
    private String geoOrigin;
    private GeolocationPermissions.Callback geoCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Lets the test tools drive the page in debug builds. Off in the release APK.
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#15130f"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(true);

        WebViewAssetLoader assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return assets.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !HOST.equals(r.getUrl().getHost()); // the app never leaves its own pages
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                if (pendingJs != null) {
                    web.evaluateJavascript(pendingJs, null);
                    pendingJs = null;
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (hasLocation()) {
                    cb.invoke(origin, true, false);
                } else {
                    geoOrigin = origin;
                    geoCallback = cb;
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        // Web pages live at an https address so storage and location behave as on the web.
        if (state != null) web.restoreState(state);
        else web.loadUrl("https://" + HOST + "/assets/index.html");
        handleAlarmIntent(getIntent());
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleAlarmIntent(i);
    }

    private void runWhenReady(String js) {
        runOnUiThread(() -> {
            if (pageReady) web.evaluateJavascript(js, null);
            else pendingJs = js;
        });
    }

    private void handleAlarmIntent(Intent i) {
        String route = i.getStringExtra(ROUTE);
        if (route != null) {
            i.removeExtra(ROUTE);
            runWhenReady("location.hash=" + JSONObject.quote(route));
            return;
        }
        String key = i.getStringExtra(Alarms.KEY);
        if (key == null) return;
        long at = i.getLongExtra(Alarms.AT, 0);
        String action = i.getStringExtra(Alarms.ACTION);
        i.removeExtra(Alarms.KEY); // so rotating or returning doesn't alarm again
        if (Alarms.PRAYED.equals(action)) Alarms.stop(this);
        else getWindow().addFlags(LOCK_FLAGS); // show over the lock screen
        showAlarm(key, at, action);
    }

    void showAlarm(String key, long at, String action) {
        runWhenReady("nativeAlarm(" + JSONObject.quote(key) + "," + at + ","
                + (action == null ? "null" : JSONObject.quote(action)) + ")");
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = this;
        web.onResume();
    }

    @Override
    protected void onPause() {
        visible = null;
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_LOCATION && geoCallback != null) {
            geoCallback.invoke(geoOrigin, hasLocation(), false);
            geoCallback = null;
        } else if (code == REQ_NOTIFICATIONS) {
            web.evaluateJavascript("if (window.renderAlarmHelp) renderAlarmHelp()", null);
        }
    }

    /** What the web app can ask of Android. These arrive on a background thread. */
    private class Bridge {
        @JavascriptInterface
        public void setAlarms(String json) {
            Alarms.save(MainActivity.this, json);
        }

        @JavascriptInterface
        public void setTodos(String json) {
            Digest.save(MainActivity.this, json);
        }

        @JavascriptInterface
        public void stopAlarm() {
            Alarms.stop(MainActivity.this);
            runOnUiThread(() -> {
                getWindow().clearFlags(LOCK_FLAGS);
                // Don't leave personal data open on top of a locked phone.
                if (getSystemService(KeyguardManager.class).isKeyguardLocked()) moveTaskToBack(true);
            });
        }

        @JavascriptInterface
        public void testAlarm(int seconds) {
            Alarms.scheduleTest(MainActivity.this, System.currentTimeMillis() + seconds * 1000L);
        }

        @JavascriptInterface
        public void requestAlarmPermissions() {
            if (Alarms.needsNotificationPermission() && !Alarms.notificationsAllowed(MainActivity.this)) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS));
            }
        }

        @JavascriptInterface
        public boolean notificationsAllowed() {
            return Alarms.notificationsAllowed(MainActivity.this);
        }

        @JavascriptInterface
        public void openAppSettings() {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }

        @JavascriptInterface
        public void setBarColor(String hex) {
            int color = Color.parseColor(hex);
            runOnUiThread(() -> {
                getWindow().setStatusBarColor(color);
                // Dark icons on a light bar, light icons on a dark bar.
                boolean lightBar = Color.luminance(color) > 0.4;
                View d = getWindow().getDecorView();
                int flags = d.getSystemUiVisibility();
                d.setSystemUiVisibility(lightBar
                        ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            });
        }
    }
}
