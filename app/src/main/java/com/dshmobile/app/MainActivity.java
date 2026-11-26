package com.dshmobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * DSH Remote —— DeepSeek Harness 手机端壳应用（个人版，双通道）。
 *
 * 连接策略：先探测「家庭局域网直连」地址（快），不通则用固定域名经 DoH 反查
 * Cloudflare 快速隧道地址（公网）。认证由服务器上的 DSH 登录插件承担：
 * 检测到 /login 页面自动填表登录（凭据存 Android Keystore 加密存储，30 天
 * Cookie 过期后无感重登）。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "dsh";
    private static final String KEY_LAN = "lan_url";
    private static final String KEY_DOMAIN = "tunnel_domain";
    private static final String KEY_USER = "user";
    private static final String KEY_PASS = "pass";
    private static final String KEY_LAN_PIN = "lan_cert_pin";
    private static final int REQ_FILE = 41;

    private WebView web;
    private SharedPreferences prefs;
    private TextView connStatus;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private long lastConnectAt = 0L;
    private int autoLoginBudget = 3;
    private boolean pinnedThisLoad = false;
    private String activeTunnelHost = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        migrateLegacyPassword();
        web = findViewById(R.id.web);
        connStatus = findViewById(R.id.connStatus);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // 移动端资源带版本号，禁缓存确保永远加载最新
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                // 自签证书仅放行「用户配置的家庭局域网地址」这一处，且比对的是
                // 出错资源自己的地址（error.getUrl()）——顶层页面是局域网地址时，
                // 页面内第三方资源的证书错误照拒不误。公网隧道域名证书有效，任何
                // SSL 错误都拒绝，防中间人。局域网地址另加证书指纹钉扎（TOFU）：
                // 首次接触记住指纹，指纹突变（可能被劫持）时拒绝加载并警告；
                // 重新信任只在「设置里点保存并连接」这条路上发生（用户确认动作）。
                String lanHost = hostOf(prefs.getString(KEY_LAN, ""));
                String errHost = hostOf(error.getUrl());
                if (lanHost != null && lanHost.equals(errHost) && lanCertTrusted(error)) {
                    handler.proceed();
                } else {
                    handler.cancel();
                    if (lanHost != null && lanHost.equals(errHost)) {
                        toast("局域网地址的证书变了（可能与服务器不一致或被劫持），已拒绝连接。" +
                                "如确认是服务器换了证书，请在设置里点一次「保存并连接」以重新信任。");
                    }
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                connStatus.setText("连接中…");
                pinnedThisLoad = false;
                gateJavascriptBridge(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                connStatus.setText("已连接");
                if (url == null || !url.endsWith("/login")) {
                    autoLoginBudget = 3; // 离开登录页即重置自动登录预算
                }
                autoLoginIfOnLoginPage(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame() && error != null) {
                    connStatus.setText("连接失败");
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            /** 文件选择（拍照/相册/文件）：DSH 页面的上传按钮依赖这里。 */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent content = new Intent(Intent.ACTION_GET_CONTENT);
                    content.addCategory(Intent.CATEGORY_OPENABLE);
                    content.setType("*/*");
                    content.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NO_HISTORY);
                    Intent chooser = Intent.createChooser(content, "选择要上传的文件");
                    Intent cam = cameraIntent(params);
                    if (cam != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                    startActivityForResult(chooser, REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        // 下载：29+ 走系统下载管理器（隧道通道证书有效，可用）；blob:/data:、
        // 26-28（公开下载目录要存储权限）、以及局域网自签地址（下载管理器过不了
        // 证书校验）统一走 JS 桥接落盘
        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            String name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
            String lanHost = hostOf(prefs.getString(KEY_LAN, ""));
            boolean lanSelfSigned = lanHost != null && lanHost.equals(hostOf(url));
            if (url.startsWith("blob:") || url.startsWith("data:") || Build.VERSION.SDK_INT < 29 || lanSelfSigned) {
                saveBlobViaJs(url, name);
            } else {
                try {
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                    if (mimetype != null && !mimetype.isEmpty()) r.setMimeType(mimetype);
                    String ck = android.webkit.CookieManager.getInstance().getCookie(url);
                    if (ck != null) r.addRequestHeader("Cookie", ck);
                    if (userAgent != null) r.addRequestHeader("User-Agent", userAgent);
                    r.setTitle(name);
                    r.setDescription("DSH 下载");
                    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    dm.enqueue(r);
                    toast("已开始下载：" + name);
                } catch (Exception e) {
                    toast("下载失败：" + e.getMessage());
                }
            }
        });
        // JS 桥不在此处常驻挂载：gateJavascriptBridge 在 onPageStarted 按页面来源挂/卸

        Button settingsBtn = findViewById(R.id.settingsBtn);
        settingsBtn.setOnClickListener(v -> showSettings());

        String lan = prefs.getString(KEY_LAN, "");
        String domain = prefs.getString(KEY_DOMAIN, "");
        if (lan.isEmpty() && domain.isEmpty()) {
            showSettings();
        } else {
            connect();
        }
    }

    // ── 连接：双通道自动选 ────────────────────────────────────────────────────────

    private void connect() {
        connect(false);
    }

    /** force=false 用于 onResume 等自动场景（10 秒内不重复探测）；设置保存走 force=true。 */
    private void connect(boolean force) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - lastConnectAt < 10_000) return;
        lastConnectAt = now;
        String lan = prefs.getString(KEY_LAN, "");
        String domain = prefs.getString(KEY_DOMAIN, "");
        if (!lan.isEmpty()) {
            connStatus.setText("探测家庭直连…");
            new Thread(() -> {
                boolean alive = lanAlive(lan);
                runOnUiThread(() -> {
                    if (alive) {
                        activeTunnelHost = null; // 走局域网通道，隧道桥面授权随之失效
                        web.loadUrl(trimSlash(lan));
                    } else {
                        connectTunnel(domain);
                    }
                });
            }).start();
        } else {
            connectTunnel(domain);
        }
    }

    private void connectTunnel(String domain) {
        if (domain == null || domain.trim().isEmpty()) {
            connStatus.setText("未配置服务器");
            return;
        }
        connStatus.setText("公网隧道发现中…");
        new Thread(() -> {
            String tunnel = dohResolve(domain.trim());
            new Handler(Looper.getMainLooper()).post(() -> {
                if (tunnel != null && !tunnel.isEmpty()) {
                    activeTunnelHost = tunnel;
                    web.loadUrl("https://" + tunnel + "/");
                } else {
                    connStatus.setText("隧道发现失败");
                    toast("无法从固定域名 " + domain + " 反查隧道地址；服务器端的隧道容器在跑吗？");
                }
            });
        }).start();
    }

    /** 探测局域网直连地址是否可达（自签证书场景，仅探测用放行任意证书）。 */
    private boolean lanAlive(String base) {
        try {
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tm, new SecureRandom());
            HttpsURLConnection c = (HttpsURLConnection) new URL(trimSlash(base) + "/login").openConnection();
            c.setSSLSocketFactory(sc.getSocketFactory());
            c.setHostnameVerifier((h, s) -> true);
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    // ── 自动登录：检测到 /login 页面就替用户提交（凭据来自加密存储） ────────────────

    private void autoLoginIfOnLoginPage(String url) {
        if (url == null || !url.endsWith("/login")) return;
        // 来源门禁：只对可信来源（配置的局域网地址 / 当前隧道地址）的登录页自动提交，
        // 防止页面里指向外部的可疑链接把存的密码发给陌生站点
        if (!isTrustedPageOrigin(url)) return;
        // 首次接触（证书指纹刚记录，无法排除敌意局域网伪装）不自动提交凭据，
        // 让用户手登一次；后续接触（指纹与首录一致）才自动提交。
        if (pinnedThisLoad) return;
        // 纵深防御：自动登录有预算上限（防登录插件将来把失败也改成重定向导致的循环重提交）
        if (autoLoginBudget <= 0) return;
        String user = prefs.getString(KEY_USER, "dsh");
        String pass = CredentialStore.decrypt(prefs.getString(KEY_PASS, ""));
        if (pass.isEmpty()) return;
        autoLoginBudget--;
        // 与登录页自身的无痕登录同款：POST 成功（303 → opaqueredirect）后原地跳回首页；
        // 失败（401 带错误提示的登录页）时不动作，让用户看到错误并可手改。
        String js = "(async () => { try { const r = await fetch('/login', { method: 'POST', redirect: 'manual',"
                + " body: new URLSearchParams({ user: " + JSONObject.quote(user) + ", pass: " + JSONObject.quote(pass) + " }) });"
                + " if (r.type === 'opaqueredirect') { location.replace('./'); return 'ok' } return 'bad' } catch (e) { return 'err' } })()";
        web.evaluateJavascript(js, null);
    }

    // ── 文件选择（含拍照） ────────────────────────────────────────────────────────

    private Intent cameraIntent(WebChromeClient.FileChooserParams params) {
        boolean wantsImage = false;
        if (params != null && params.getAcceptTypes() != null) {
            for (String t : params.getAcceptTypes()) {
                if (t != null && t.startsWith("image/")) { wantsImage = true; break; }
            }
        }
        if (!wantsImage) return null;
        try {
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            File dir = new File(getCacheDir(), "photos");
            if (!dir.exists()) dir.mkdirs();
            // 顺手清掉旧拍照缓存（每次拍照只新增不清理会累积）
            File[] stale = dir.listFiles();
            if (stale != null) for (File f : stale) f.delete();
            File file = new File(dir, "capture_" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            cam.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            cam.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
            return cam;
        } catch (Exception e) {
            cameraUri = null;
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                if (data != null && data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data != null && data.getData() != null) {
                    results = new Uri[]{data.getData()};
                } else if (cameraUri != null) {
                    results = new Uri[]{cameraUri};
                }
            }
            cameraUri = null;
            fileCallback.onReceiveValue(results);
            fileCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // ── 下载落盘（blob:/data: 经 JS 桥接） ─────────────────────────────────────────

    private void saveBlobViaJs(String url, String name) {
        String js = "(async () => { try { const r = await fetch(" + JSONObject.quote(url) + ");"
                + " const b = await r.blob(); const fr = new FileReader();"
                + " fr.onloadend = () => AndroidBlob.save(" + JSONObject.quote(name) + ", String(fr.result).split(',')[1] || '');"
                + " fr.readAsDataURL(b); return 'ok' } catch (e) { return 'err:' + e } })()";
        web.evaluateJavascript(js, null);
        toast("正在保存 " + name);
    }

    /** JS 桥接：网页侧把 blob 内容转 base64 交到这里，写入系统「下载」目录。 */
    public class BlobSaver {
        @JavascriptInterface
        public void save(String name, String base64) {
            try {
                byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizeFileName(name));
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, guessMime(name));
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os != null) os.write(bytes);
                    }
                } else {
                    // API 26-28：写入应用专属外部目录，免存储权限
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir != null) {
                        if (!dir.exists()) dir.mkdirs();
                        File out = new File(dir, sanitizeFileName(name));
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            fos.write(bytes);
                        }
                    }
                }
                runOnUiThread(() -> toast("已保存：" + name));
            } catch (Exception e) {
                runOnUiThread(() -> toast("保存失败：" + e.getMessage()));
            }
        }
    }

    /**
     * 文件名清洗（防路径穿越/非法字符）：取 basename、剥路径与盘符、剔除引号等
     * 危险字符、拒绝空名与点名；桥方法对页面内任意 JS 暴露，name 不可信。
     */
    static String sanitizeFileName(String raw) {
        String name = raw == null ? "" : raw;
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\"*/:<>?\\\\|\\[\\]]", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "download.bin";
        if (name.length() > 100) name = name.substring(name.length() - 100);
        return name;
    }

    private String guessMime(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    // ── 服务器设置 ────────────────────────────────────────────────────────────────

    private void showSettings() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        ll.setPadding(pad, pad / 2, pad, 0);

        EditText lan = new EditText(this);
        lan.setHint("家庭直连地址，如 https://192.168.1.10:3080（可留空）");
        lan.setSingleLine(true);

        EditText domain = new EditText(this);
        domain.setHint("固定域名，如 your-domain.de5.net（公网通道）");
        domain.setSingleLine(true);

        EditText user = new EditText(this);
        user.setHint("用户名");
        user.setSingleLine(true);

        EditText pass = new EditText(this);
        pass.setHint("密码（已保存，留空则不修改）");
        pass.setSingleLine(true);

        // 不回填已存值：公开截图不泄露私人地址与凭据；留空保存=保留原值
        String oldLan = prefs.getString(KEY_LAN, "");
        String oldDomain = prefs.getString(KEY_DOMAIN, "");
        if (!oldLan.isEmpty()) lan.setHint("家庭直连地址（已配置，留空保留）");
        if (!oldDomain.isEmpty()) domain.setHint("固定域名（已配置，留空保留）");

        ll.addView(lan);
        ll.addView(domain);
        ll.addView(user);
        ll.addView(pass);

        new AlertDialog.Builder(this)
                .setTitle("DSH 服务器设置")
                .setMessage("两条通道：家庭 WiFi 直连地址优先；在外面自动走固定域名反查隧道。\n留空的项保留原值。")
                .setPositiveButton("保存并连接", (d, w) -> {
                    String newLan = normalizeLanUrl(lan.getText().toString().trim());
                    String newDomain = normalizeDomain(domain.getText().toString().trim());
                    String newUser = user.getText().toString().trim();
                    String newPass = pass.getText().toString();
                    String oldUser = prefs.getString(KEY_USER, "dsh");
                    String oldPass = CredentialStore.decrypt(prefs.getString(KEY_PASS, ""));
                    String encPass = CredentialStore.encrypt(newPass.isEmpty() ? oldPass : newPass);
                    prefs.edit()
                            .putString(KEY_LAN, newLan.isEmpty() ? oldLan : newLan)
                            .putString(KEY_DOMAIN, newDomain.isEmpty() ? oldDomain : newDomain)
                            .putString(KEY_USER, newUser.isEmpty() ? oldUser : newUser)
                            .putString(KEY_PASS, encPass == null ? "" : encPass)
                            // 用户主动保存 = 主动重新信任：清掉局域网证书指纹，下次接触重录（TOFU 重钉的唯一入口）
                            .remove(KEY_LAN_PIN)
                            .apply();
                    connect(true);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 杂项 ──────────────────────────────────────────────────────────────────────

    private void migrateLegacyPassword() {
        String p = prefs.getString(KEY_PASS, "");
        if (!p.isEmpty() && !CredentialStore.isEncrypted(p)) {
            String enc = CredentialStore.encrypt(p);
            if (enc != null) prefs.edit().putString(KEY_PASS, enc).apply();
        }
    }

    /** 通过阿里 DoH（国内可达）查固定域名的 CNAME/TXT，提取当前隧道域名。 */
    private String dohResolve(String host) {
        try {
            String q = Uri.encode(host);
            URL u = new URL("https://dns.alidns.com/resolve?name=" + q + "&type=TXT");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/dns-json");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            if (code != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            JSONObject j = new JSONObject(bos.toString(StandardCharsets.UTF_8.name()));
            JSONArray ans = j.optJSONArray("Answer");
            if (ans == null) return null;
            for (int i = 0; i < ans.length(); i++) {
                JSONObject a = ans.optJSONObject(i);
                if (a == null) continue;
                int type = a.optInt("type", -1);
                String data = a.optString("data", "");
                if ((type == 5 || type == 16) && data.contains("trycloudflare.com")) {
                    return data.replaceFirst("\\.$", "").trim();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimSlash(String u) {
        String s = u.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 局域网证书钉扎（TOFU）：首录指纹，之后指纹一致才放行；首次接触返回 true 但标记「本次刚钉」。 */
    private boolean lanCertTrusted(android.net.http.SslError error) {
        try {
            byte[] der = error.getCertificate().getEncoded();
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            String fp = sb.toString();
            String stored = prefs.getString(KEY_LAN_PIN, "");
            if (stored.isEmpty()) {
                prefs.edit().putString(KEY_LAN_PIN, fp).apply();
                pinnedThisLoad = true;
                return true;
            }
            return stored.equals(fp);
        } catch (Exception e) {
            return false;
        }
    }

    /** 页面 URL 是否来自可信来源（配置的局域网地址 / 当前隧道地址，完整 origin 比对）。 */
    private boolean isTrustedPageOrigin(String pageUrl) {
        String pageOrigin = originOf(pageUrl);
        String lanOrigin = originOf(prefs.getString(KEY_LAN, ""));
        String tunnelOrigin = activeTunnelHost == null ? null : originOf("https://" + activeTunnelHost);
        return (lanOrigin != null && lanOrigin.equals(pageOrigin))
                || (tunnelOrigin != null && tunnelOrigin.equals(pageOrigin));
    }

    /** JS 桥面门禁：只有可信来源的页面能拿到 AndroidBlob 桥。 */
    private void gateJavascriptBridge(String pageUrl) {
        if (isTrustedPageOrigin(pageUrl)) {
            web.addJavascriptInterface(new BlobSaver(), "AndroidBlob");
        } else {
            web.removeJavascriptInterface("AndroidBlob");
        }
    }

    /** 家庭直连地址规整：忘写协议头时默认补 https://。 */
    private static String normalizeLanUrl(String input) {
        String s = trimSlash(input);
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        return s;
    }

    /** 固定域名规整：剥掉协议头与路径，只留裸域名（DoH 反查的输入形态）。 */
    private static String normalizeDomain(String input) {
        String s = input.trim();
        if (s.isEmpty()) return "";
        s = s.replaceFirst("(?i)^https?://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s.trim();
    }

    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) return null;
        try { return new URL(url).getHost(); } catch (Exception e) { return null; }
    }

    /** 完整 origin（协议+主机+端口，默认端口归一）：比只比 host 更严，防同主机异端口冒用。 */
    private static String originOf(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            URL u = new URL(url);
            String scheme = u.getProtocol();
            int port = u.getPort();
            int defaultPort = "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
            boolean showPort = port != -1 && port != defaultPort;
            return scheme + "://" + u.getHost() + (showPort ? ":" + port : "");
        } catch (Exception e) {
            return null;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null && web.getUrl() == null
                && (!prefs.getString(KEY_LAN, "").isEmpty() || !prefs.getString(KEY_DOMAIN, "").isEmpty())) {
            connect(false);
        }
    }

    @Override
    protected void onDestroy() {
        // 进程级重建时回收文件选择回调，避免孤儿回调持有 Activity 引用
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        cameraUri = null;
        super.onDestroy();
    }
}
