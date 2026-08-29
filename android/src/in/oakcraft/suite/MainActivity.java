package in.oakcraft.suite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * OakCraft Suite — master launcher.
 *
 * The UI (www/index.html) runs in a WebView served from a private https origin. It reads the app
 * catalogue (apps.json) from the Suite repo, checks each app's newest GitHub Release and, through
 * the "Suite" JS bridge below, installs / updates / opens the individual OakCraft apps.
 */
public class MainActivity extends Activity {

    private static final String TAG = "OakCraftSuite";
    static final String APP_HOST = "suite.oakcraft.app";
    static final String START_URL = "https://" + APP_HOST + "/index.html";
    private static final int RC_UNKNOWN_SOURCES = 2001;

    private WebView web;
    private long lastBack = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String pendingInstall;   // file name waiting for the "install unknown apps" permission

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(web);
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString() + " OakCraftSuite/" + versionName() + " (Android)");
        WebView.setWebContentsDebuggingEnabled(false);

        web.addJavascriptInterface(new Bridge(), "Suite");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        if (web.getUrl() == null) web.loadUrl(START_URL);
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }
    @Override protected void onResume() { super.onResume(); web.onResume(); js("if(window.Suite_onResume)Suite_onResume();"); }
    @Override protected void onPause() { web.onPause(); super.onPause(); }
    @Override protected void onDestroy() { try { web.destroy(); } catch (Exception ignored) {} super.onDestroy(); }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "1.0"; }
    }
    private void js(final String code) {
        ui.post(new Runnable() { @Override public void run() { try { web.evaluateJavascript(code, null); } catch (Exception ignored) {} } });
    }
    private static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "") + "'";
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(function(){try{return (window.Suite_back&&Suite_back())?'1':'0';}catch(e){return '0';}})()",
            new ValueCallback<String>() {
                @Override public void onReceiveValue(String v) {
                    if (v != null && v.indexOf('1') >= 0) return;
                    long now = System.currentTimeMillis();
                    if (now - lastBack < 2200) { finish(); return; }
                    lastBack = now;
                    Toast.makeText(MainActivity.this, R.string.press_back_again, Toast.LENGTH_SHORT).show();
                }
            });
    }

    /* ------------------------------------------------------------------ assets ------- */
    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (u != null && APP_HOST.equalsIgnoreCase(u.getHost())) return serveAsset(u);
            return super.shouldInterceptRequest(view, request);
        }
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri u = Uri.parse(url);
            if (APP_HOST.equalsIgnoreCase(u.getHost())) return false;
            String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            if ("blob".equals(sc) || "data".equals(sc) || "about".equals(sc) || "javascript".equals(sc)) return false;
            openExternal(url);
            return true;
        }
    }
    private WebResourceResponse serveAsset(Uri u) {
        String path = u.getPath();
        if (path == null || path.length() == 0 || path.equals("/")) path = "/index.html";
        if (path.endsWith("/")) path += "index.html";
        try {
            InputStream in = getAssets().open("www" + path);
            Map<String, String> h = new HashMap<String, String>();
            h.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse(mimeFor(path), "utf-8", 200, "OK", h, in);
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<String, String>(), new ByteArrayInputStream("Not found".getBytes()));
        }
    }
    private static String mimeFor(String p) {
        String l = p.toLowerCase();
        if (l.endsWith(".html")) return "text/html";
        if (l.endsWith(".js")) return "application/javascript";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Log.w(TAG, "openExternal", e); }
    }

    private class Chrome extends WebChromeClient {
        @Override public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
            new AlertDialog.Builder(MainActivity.this).setMessage(msg).setCancelable(false)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { r.confirm(); } }).show();
            return true;
        }
        @Override public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
            new AlertDialog.Builder(MainActivity.this).setMessage(msg).setCancelable(false)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { r.confirm(); } })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { r.cancel(); } }).show();
            return true;
        }
        @Override public boolean onConsoleMessage(ConsoleMessage m) { Log.d(TAG, "[web] " + m.message()); return true; }
    }

    /* ------------------------------------------------------------- install / launch ----- */
    private boolean canInstall() {
        if (Build.VERSION.SDK_INT < 26) return true;
        try { return (Boolean) PackageManager.class.getMethod("canRequestPackageInstalls").invoke(getPackageManager()); }
        catch (Exception e) { return true; }
    }
    private void doInstall(String fileName) {
        File f = ShareProvider.fileFor(this, fileName);
        if (!f.exists() || f.length() == 0) { toast(getString(R.string.file_missing)); return; }
        if (!canInstall()) {
            pendingInstall = fileName;
            try {
                Intent i = new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, RC_UNKNOWN_SOURCES);
                toast(getString(R.string.allow_unknown));
            } catch (Exception e) { toast(getString(R.string.allow_unknown)); }
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(ShareProvider.uriFor(fileName), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) { Log.w(TAG, "install", e); toast(getString(R.string.install_failed)); }
    }
    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        if (rc == RC_UNKNOWN_SOURCES) {
            String p = pendingInstall; pendingInstall = null;
            if (p != null && canInstall()) doInstall(p);
            return;
        }
        super.onActivityResult(rc, res, data);
    }
    private void toast(final String t) {
        ui.post(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, t, Toast.LENGTH_SHORT).show(); } });
    }

    /* ------------------------------------------------------------------- bridge ----- */
    private class Bridge {
        @JavascriptInterface public String getVersion() { return versionName(); }
        @JavascriptInterface public int getVersionCode() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
        }
        @JavascriptInterface public String getPackage() { return getPackageName(); }
        @JavascriptInterface public String getPlatform() { return "android"; }
        @JavascriptInterface public int getSdk() { return Build.VERSION.SDK_INT; }
        @JavascriptInterface public void toast(String t) { MainActivity.this.toast(t == null ? "" : t); }
        @JavascriptInterface public void openExternal(final String url) {
            ui.post(new Runnable() { @Override public void run() { MainActivity.this.openExternal(url); } });
        }

        /** Installed state of one package as JSON: {installed, versionName, versionCode, updatedAt} */
        @JavascriptInterface public String appInfo(String pkg) {
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
                return "{\"installed\":true,\"versionName\":" + json(pi.versionName) + ",\"versionCode\":" + pi.versionCode
                    + ",\"updatedAt\":" + pi.lastUpdateTime + "}";
            } catch (Exception e) { return "{\"installed\":false}"; }
        }
        @JavascriptInterface public boolean launch(final String pkg) {
            try {
                final Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return false;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ui.post(new Runnable() { @Override public void run() { try { startActivity(i); } catch (Exception e) { toast(getString(R.string.no_app)); } } });
                return true;
            } catch (Exception e) { return false; }
        }
        /**
         * Downloads an APK (follows GitHub redirects). Progress -> Suite_onProgress(id, pct);
         * when done -> Suite_onDownloaded(id, fileName, {package, versionCode, versionName}) and the page
         * decides whether to call installFile(fileName). Errors -> Suite_onError(id, message).
         */
        @JavascriptInterface public void downloadAndInstall(final String id, final String url, final String fileName) {
            new Thread(new Runnable() { @Override public void run() {
                HttpURLConnection c = null;
                try {
                    String name = ShareProvider.safeName(fileName == null || fileName.length() == 0 ? (id + ".apk") : fileName);
                    if (!name.toLowerCase().endsWith(".apk")) name += ".apk";
                    File f = ShareProvider.fileFor(MainActivity.this, name);
                    File[] old = ShareProvider.dir(MainActivity.this).listFiles();      // keep the cache small
                    if (old != null) for (File o : old) if (o.getName().toLowerCase().endsWith(".apk") && !o.equals(f)) o.delete();
                    URL u = new URL(url);
                    int hops = 0;
                    while (true) {
                        c = (HttpURLConnection) u.openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(20000); c.setReadTimeout(60000);
                        c.setRequestProperty("User-Agent", "OakCraftSuite/" + versionName());
                        c.setRequestProperty("Accept", "application/octet-stream");
                        int code = c.getResponseCode();
                        if (code >= 300 && code < 400 && hops++ < 6) {
                            String loc = c.getHeaderField("Location");
                            c.disconnect();
                            u = new URL(u, loc);
                            continue;
                        }
                        if (code != 200) throw new IOException("HTTP " + code);
                        break;
                    }
                    long total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    FileOutputStream out = new FileOutputStream(f);
                    byte[] buf = new byte[65536]; long done = 0; int n; int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n); done += n;
                        int pct = total > 0 ? (int) (done * 100 / total) : -1;
                        if (pct != lastPct) { lastPct = pct; js("if(window.Suite_onProgress)Suite_onProgress(" + q(id) + "," + pct + ");"); }
                    }
                    out.flush(); out.close(); in.close();
                    if (total > 0 && done < total) throw new IOException("incomplete download");
                    ApkInfo ai = ApkInfo.read(f);
                    if (ai == null) { f.delete(); throw new IOException("downloaded file is not a valid APK"); }
                    js("if(window.Suite_onDownloaded)Suite_onDownloaded(" + q(id) + "," + q(name) + "," + q(ai.toJson()) + ");");
                } catch (Exception e) {
                    Log.w(TAG, "download failed", e);
                    js("if(window.Suite_onError)Suite_onError(" + q(id) + "," + q(String.valueOf(e.getMessage())) + ");");
                } finally { if (c != null) try { c.disconnect(); } catch (Exception ignored) {} }
            } }).start();
        }
        @JavascriptInterface public void installFile(final String fileName) {
            ui.post(new Runnable() { @Override public void run() { doInstall(fileName); } });
        }
        /** {package, versionCode, versionName} of a downloaded APK, or {} if unreadable. */
        @JavascriptInterface public String apkInfo(String fileName) {
            ApkInfo ai = ApkInfo.read(ShareProvider.fileFor(MainActivity.this, fileName));
            return ai == null ? "{}" : ai.toJson();
        }
        @JavascriptInterface public void uninstall(final String pkg) {
            ui.post(new Runnable() { @Override public void run() {
                try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { toast(getString(R.string.no_app)); }
            } });
        }
        @JavascriptInterface public String bundledCatalog() {
            try {
                InputStream in = getAssets().open("www/apps.json");
                byte[] b = new byte[in.available()]; int r = in.read(b); in.close();
                return new String(b, 0, Math.max(r, 0), "UTF-8");
            } catch (Exception e) { return "{}"; }
        }
    }
    private static String json(String s) { return jsonStr(s); }
    static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
