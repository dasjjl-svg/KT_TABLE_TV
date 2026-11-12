package net.quber.afactorymode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

public final class AFactoryModeMainActivity extends Activity {
    private static final String OFFLINE_DOCUMENT_URL = PanelBuildConfig.ORIGIN + "/device/offline";
    private final Handler handler = new Handler();
    private final PanelPolicy policy = new PanelPolicy(PanelBuildConfig.ORIGIN);
    private WebView web;
    private ScrollView overlay;
    private TextView message;
    private Button retryButton;
    private AlertDialog options;
    private AlertDialog collectionDialog;
    private KtApkExport.Result pendingCollectionResult;
    private String pendingCollectionTitle;
    private boolean foreground, connecting, pageReady, registered, failed, hasPanelPage, offlinePage;
    private int attempt, generation;
    private boolean backPending;
    private int backRequest;
    private Runnable backFallback;
    private int standbyRequest;
    private Runnable standbyFallback;
    private String loadingUrl;
    private final Runnable retry = new Runnable() { public void run() { connect(); } };
    private final Runnable loadTimeout = new Runnable() {
        public void run() { if (!pageReady && foreground) fail(); }
    };
    private final BroadcastReceiver network = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo active = manager == null ? null : manager.getActiveNetworkInfo();
            if (foreground && !connecting && !pageReady && active != null && active.isConnected()) connect();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setSaveFormData(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUserAgentString(settings.getUserAgentString() + " HomePanelKiosk/1.0");
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, false);
        WebView.setWebContentsDebuggingEnabled(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (policy.reconnect(url)) { pageReady = false; connect(); return true; }
                return !policy.sameOrigin(url);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (policy.sameOrigin(request.getUrl().toString())) return null;
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                        java.util.Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (connecting || offlinePage || OFFLINE_DOCUMENT_URL.equals(url) || "about:blank".equals(url)) return;
                if (!policy.sameOrigin(url)) { view.stopLoading(); fail(); return; }
                loadingUrl = url;
                hasPanelPage = false;
                pageReady = false;
                failed = false;
                handler.removeCallbacks(loadTimeout);
                handler.postDelayed(loadTimeout, 20000);
            }
            @Override public void onPageFinished(final WebView view, final String url) {
                if (offlinePage || OFFLINE_DOCUMENT_URL.equals(url)) return;
                if (!foreground || connecting || failed || !policy.sameOrigin(url) || policy.reconnect(url)) return;
                final int pageGeneration = generation;
                view.evaluateJavascript("(function(){return !!(document.getElementById('refresh') && document.getElementById('living-on') && typeof createWasherAlertTracker === 'function');}())",
                        new ValueCallback<String>() {
                            @Override public void onReceiveValue(String result) {
                                if (pageGeneration != generation || !foreground || connecting || failed
                                        || isFinishing() || isDestroyed() || !url.equals(view.getUrl())) return;
                                if (!"true".equals(result)) return;
                                handler.removeCallbacks(loadTimeout);
                                handler.removeCallbacks(retry);
                                pageReady = true;
                                hasPanelPage = true;
                                attempt = 0;
                                overlay.setVisibility(View.GONE);
                                web.setVisibility(View.VISIBLE);
                                if ((options == null || !options.isShowing())
                                        && (collectionDialog == null || !collectionDialog.isShowing())) web.requestFocus();
                            }
                        });
            }
            @Override public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (!connecting && !offlinePage && failingUrl != null && failingUrl.equals(loadingUrl)) fail();
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
                sslHandler.cancel();
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        overlay = new ScrollView(this);
        overlay.setFillViewport(true);
        overlay.setBackgroundColor(Color.BLACK);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(42), dp(30), dp(42), dp(30));
        overlay.addView(content, new ScrollView.LayoutParams(-1, -2));
        TextView title = new TextView(this);
        title.setText("홈 패널"); title.setTextColor(Color.WHITE); title.setTextSize(30);
        content.addView(title);
        message = new TextView(this);
        message.setTextColor(Color.LTGRAY); message.setTextSize(20);
        message.setPadding(0, dp(14), 0, dp(20));
        content.addView(message);
        retryButton = addButton(content, "다시 연결", new Runnable() { public void run() { attempt = 0; connect(); } });
        if (MaintenancePolicy.BOOTSTRAP_PACKAGE.equals(getPackageName())) {
            addButton(content, "원격 업데이트·자동 시작 설정", new Runnable() { public void run() { startMaintenance(); } });
        }
        addButton(content, "관리", new Runnable() { public void run() { showManagement(); } });
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        if (!showOfflinePage()) showMessage("서버에 연결하는 중입니다.");
        MaintenanceService.start(this, false);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        web.onResume();
        if (!registered) { registerReceiver(network, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)); registered = true; }
        if (!pageReady && !connecting) connect();
        showCollectionResult();
    }

    @Override protected void onPause() {
        foreground = false;
        cancelBackRequest();
        cancelStandbyRequest();
        handler.removeCallbacks(retry);
        handler.removeCallbacks(loadTimeout);
        if (registered) { unregisterReceiver(network); registered = false; }
        web.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (options != null) options.dismiss();
        if (collectionDialog != null) collectionDialog.dismiss();
        pendingCollectionResult = null;
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }

    private void connect() {
        if (!foreground || connecting || isFinishing() || isDestroyed()) return;
        handler.removeCallbacks(retry);
        handler.removeCallbacks(loadTimeout);
        connecting = true;
        pageReady = false;
        failed = false;
        if (!hasPanelPage && !showOfflinePage()) showMessage("서버에 연결하는 중입니다.");
        final int requestGeneration = ++generation;
        new Thread(new Runnable() {
            @Override public void run() {
                String result = null;
                try { result = bootstrap(); } catch (Exception ignored) { }
                final String cookie = result;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (requestGeneration != generation || isFinishing() || isDestroyed()) return;
                        connecting = false;
                        if (!foreground) return;
                        if (cookie == null) { fail(); return; }
                        CookieManager.getInstance().setCookie(PanelBuildConfig.ORIGIN, cookie, new ValueCallback<Boolean>() {
                            @Override public void onReceiveValue(Boolean accepted) {
                                if (requestGeneration != generation || !foreground || isFinishing() || isDestroyed()) return;
                                if (!Boolean.TRUE.equals(accepted)) { fail(); return; }
                                CookieManager.getInstance().flush();
                                offlinePage = false;
                                hasPanelPage = false;
                                loadingUrl = PanelBuildConfig.ORIGIN + "/";
                                handler.removeCallbacks(loadTimeout);
                                handler.postDelayed(loadTimeout, 20000);
                                web.loadUrl(PanelBuildConfig.ORIGIN + "/");
                            }
                        });
                    }
                });
            }
        }, "panel-session").start();
    }

    private String bootstrap() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(PanelBuildConfig.ORIGIN + "/api/device/session").openConnection(java.net.Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + PanelBuildConfig.TOKEN);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(2);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(new byte[]{'{', '}'}); }
            if (connection.getResponseCode() != 200) return null;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (body.size() + count > 4096) return null;
                    body.write(buffer, 0, count);
                }
            }
            if (!Boolean.TRUE.equals(new JSONObject(new String(body.toByteArray(), "UTF-8")).opt("ok"))) return null;
            String cookie = null;
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() == null || !"set-cookie".equalsIgnoreCase(header.getKey())) continue;
                for (String value : header.getValue()) {
                    String candidate = PanelPolicy.sessionCookie(value);
                    if (candidate != null) { if (cookie != null) return null; cookie = candidate; }
                }
            }
            return cookie;
        } finally { connection.disconnect(); }
    }

    private void fail() {
        failed = true;
        pageReady = false;
        handler.removeCallbacks(loadTimeout);
        handler.removeCallbacks(retry);
        if (isFinishing() || isDestroyed()) return;
        long delay = PanelPolicy.retryDelay(attempt);
        if (attempt < 4) attempt++;
        if (!hasPanelPage && !showOfflinePage()) {
            showMessage("서버에 연결하지 못했습니다.\nMac과 네트워크 연결을 확인해 주세요. " + (delay / 1000) + "초 후 다시 연결합니다.");
        }
        if (foreground) handler.postDelayed(retry, delay);
    }

    private boolean showOfflinePage() {
        if (offlinePage) return true;
        try (InputStream input = getAssets().open("offline-panel.html")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 512 * 1024) return false;
                bytes.write(buffer, 0, count);
            }
            String html = new String(bytes.toByteArray(), "UTF-8");
            offlinePage = true;
            hasPanelPage = false;
            pageReady = false;
            web.stopLoading();
            web.loadDataWithBaseURL(OFFLINE_DOCUMENT_URL, html, "text/html", "UTF-8", OFFLINE_DOCUMENT_URL);
            overlay.setVisibility(View.GONE);
            web.setVisibility(View.VISIBLE);
            if ((options == null || !options.isShowing())
                    && (collectionDialog == null || !collectionDialog.isShowing())) web.requestFocus();
            return true;
        } catch (Exception unavailable) {
            offlinePage = false;
            return false;
        }
    }

    private void showMessage(String text) {
        boolean first = overlay.getVisibility() != View.VISIBLE;
        message.setText(text);
        overlay.setVisibility(View.VISIBLE);
        web.setVisibility(View.INVISIBLE);
        if (first || getCurrentFocus() == null) retryButton.requestFocus();
    }

    @Override public void onBackPressed() {
        if (!foreground || isFinishing() || isDestroyed() || backPending) return;
        if (!pageReady) { showOptions(); return; }
        final int request = ++backRequest;
        final int pageGeneration = generation;
        final String pageUrl = web.getUrl();
        backPending = true;
        backFallback = new Runnable() {
            @Override public void run() {
                if (request != backRequest) return;
                boolean current = currentBackPage(pageGeneration, pageUrl);
                cancelBackRequest();
                if (current) showOptions();
            }
        };
        handler.postDelayed(backFallback, 1500);
        web.evaluateJavascript("(function(){if(typeof window.requestHomePanelBack==='function')return window.requestHomePanelBack()===true;var button=document.getElementById('completion-confirm');if(!button || button.disabled || !(button.offsetWidth || button.offsetHeight || button.getClientRects().length))return false;button.focus();return document.activeElement===button;}())",
                new ValueCallback<String>() {
                    @Override public void onReceiveValue(String result) {
                        if (request != backRequest) return;
                        boolean current = currentBackPage(pageGeneration, pageUrl);
                        cancelBackRequest();
                        if (current && !"true".equals(result)) showOptions();
                    }
                });
    }

    private boolean currentBackPage(int pageGeneration, String pageUrl) {
        return pageGeneration == generation && foreground && pageReady && !connecting && !failed
                && !isFinishing() && !isDestroyed() && pageUrl != null && pageUrl.equals(web.getUrl());
    }

    private void cancelBackRequest() {
        backPending = false;
        backRequest++;
        if (backFallback != null) handler.removeCallbacks(backFallback);
        backFallback = null;
    }

    private void requestStandby() {
        cancelStandbyRequest();
        if (!foreground || isFinishing() || isDestroyed()) return;
        if (!pageReady || connecting || failed) { showStandbyUnavailable(); return; }
        final int request = ++standbyRequest;
        final int pageGeneration = generation;
        final String pageUrl = web.getUrl();
        standbyFallback = new Runnable() {
            @Override public void run() {
                if (request != standbyRequest) return;
                boolean current = currentBackPage(pageGeneration, pageUrl);
                cancelStandbyRequest();
                if (current) showStandbyUnavailable();
            }
        };
        handler.postDelayed(standbyFallback, 1500);
        web.evaluateJavascript("(function(){return typeof window.requestHomePanelStandby==='function' && window.requestHomePanelStandby()===true;}())",
                new ValueCallback<String>() {
                    @Override public void onReceiveValue(String result) {
                        if (request != standbyRequest) return;
                        boolean current = currentBackPage(pageGeneration, pageUrl);
                        cancelStandbyRequest();
                        if (!current) return;
                        if (!"true".equals(result)) showStandbyUnavailable();
                        else focusPanel();
                    }
                });
    }

    private void cancelStandbyRequest() {
        standbyRequest++;
        if (standbyFallback != null) handler.removeCallbacks(standbyFallback);
        standbyFallback = null;
    }

    private void showStandbyUnavailable() {
        Toast.makeText(this, "진행 중인 작업이나 알림을 확인한 뒤 다시 시도해 주세요. 화면 연결을 기다리는 중에는 대기 화면을 열 수 없습니다.", Toast.LENGTH_LONG).show();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) return true;
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() > 0) return true;
        return super.onKeyDown(keyCode, event);
    }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) { showOptions(); return true; }
        return super.onKeyUp(keyCode, event);
    }

    private void showOptions() {
        cancelBackRequest();
        cancelStandbyRequest();
        String[] items = MaintenancePolicy.BOOTSTRAP_PACKAGE.equals(getPackageName())
                ? new String[]{"돌아가기", "패널 새로고침", "대기 화면", "관리", "홈 패널 설치·업데이트"}
                : new String[]{"돌아가기", "패널 새로고침", "대기 화면", "관리"};
        showMenu("홈 패널", items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                if (which == 1) { attempt = 0; connect(); }
                if (which == 2) requestStandby();
                if (which == 3) showManagement();
                if (which == 4) startMaintenance();
            }
        }, null);
    }

    private void showManagement() {
        String setupLabel = MaintenancePolicy.PACKAGE.equals(getPackageName()) ? "기본 홈·자동 시작 설정" : "홈 패널 설치·업데이트";
        showMenu("관리", new String[]{"돌아가기", "관리 상태", "Android 설정", setupLabel, "고급 진단", "기존 홈으로 복원", "패널 닫기"},
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) showOptions();
                        if (which == 1) showMaintenance();
                        if (which == 2) openSettings(Settings.ACTION_SETTINGS);
                        if (which == 3) startMaintenance();
                        if (which == 4) showDiagnostics();
                        if (which == 5) restoreMaintenance();
                        if (which == 6) finish();
                    }
                }, new Runnable() { public void run() { showOptions(); } });
    }

    private void showDiagnostics() {
        showMenu("고급 진단", new String[]{"돌아가기", "개발자 옵션", "홈 앱 선택창", "기본 홈 선택", "KT 앱 분석 파일 보내기",
                "기가지니 홈 앱 분석 파일 보내기", "USB·ADB 분석 파일 보내기", "네트워크 ADB 연결 시험", "ADB 실행 환경 확인",
                "시스템 구성·백업", "SPI 칩 식별", "SPI 4MiB 백업", "SPI 읽기 진단"},
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) showManagement();
                        if (which == 1) openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                        if (which == 2) showHomeChooser();
                        if (which == 3) openSettings(Settings.ACTION_HOME_SETTINGS);
                        if (which == 4) startKtApkExport();
                        if (which == 5) startKtApkExport(true);
                        if (which == 6) startSettingsApkExport();
                        if (which == 7) startNetworkAdb();
                        if (which == 8) startServiceInspection();
                        if (which == 9) startSystemBackup();
                        if (which == 10) startSpiIdentity();
                        if (which == 11) startSpiBackup();
                        if (which == 12) startSpiSamples();
                    }
                }, new Runnable() { public void run() { showManagement(); } });
    }

    private void showMenu(String title, String[] items, DialogInterface.OnClickListener listener, final Runnable onBack) {
        if (!foreground || isFinishing() || isDestroyed()) return;
        if (options != null) options.dismiss();
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setItems(items, listener).create();
        options = dialog;
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface dismissed) { if (onBack != null) onBack.run(); }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dismissed) {
                if (options != dialog) return;
                options = null;
                focusPanel();
            }
        });
        dialog.show();
    }

    private void focusPanel() {
        if (!foreground || isFinishing() || isDestroyed()
                || (options != null && options.isShowing())
                || (collectionDialog != null && collectionDialog.isShowing())) return;
        if (overlay.getVisibility() == View.VISIBLE) retryButton.requestFocus();
        else if (web.getVisibility() == View.VISIBLE) web.requestFocus();
    }

    private void showHomeChooser() {
        final Intent chooser = new Intent(Intent.ACTION_MAIN);
        chooser.addCategory(Intent.CATEGORY_HOME);
        chooser.setComponent(new ComponentName("android", "com.android.internal.app.ResolverActivity"));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!canOpenHomeChooser(chooser)) { showHomeChooserUnavailable(); return; }
        new AlertDialog.Builder(this).setTitle("홈 앱 선택창")
                .setMessage("목록에서 ‘홈 패널’을 고르고 ‘항상’을 선택하세요.\n\n바로 패널로 돌아오면 이 선택창을 한 번 더 열어 ‘홈 패널’의 ‘항상’을 선택하세요.")
                .setPositiveButton("선택창 열기", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        try { startActivity(chooser); }
                        catch (ActivityNotFoundException error) { showHomeChooserUnavailable(); }
                        catch (SecurityException error) { showHomeChooserUnavailable(); }
                    }
                }).setNegativeButton("취소", null).show();
    }

    private void startMaintenance() {
        if (MaintenancePolicy.PACKAGE.equals(getPackageName())) {
            new AlertDialog.Builder(this).setTitle("기본 홈·자동 시작 설정")
                    .setMessage("기본 홈을 홈 패널로 바꾸고 재부팅 후 자동으로 엽니다. 기존 홈 설정은 복원용으로 저장합니다. 원격 업데이트도 확인합니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("설정", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) { beginMaintenance(); }
                    }).show();
            return;
        }
        beginMaintenance();
    }

    private void beginMaintenance() {
        MaintenanceService.start(this, true);
        Toast.makeText(this, "Mac에서 홈 패널 설치 파일을 확인합니다. 설치 중에는 전원을 유지해 주세요.", Toast.LENGTH_LONG).show();
    }

    private void showMaintenance() {
        android.content.SharedPreferences state = getSharedPreferences("maintenance", MODE_PRIVATE);
        String text = MaintenancePolicy.PACKAGE.equals(getPackageName()) ? "독립 설치된 홈 패널" : "USB 설치 도우미";
        try { text += "\n버전: " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        text += "\n상태: " + state.getString("status", "대기") + "\n결과: " + state.getString("reason", "아직 실행하지 않음");
        text += "\n기본 홈: " + state.getString("home", "unknown");
        new AlertDialog.Builder(this).setTitle("관리 상태").setMessage(text).setPositiveButton("확인", null).show();
    }

    private void restoreMaintenance() {
        if (!MaintenancePolicy.PACKAGE.equals(getPackageName())) {
            Intent managed = new Intent().setComponent(new ComponentName(MaintenancePolicy.PACKAGE, MaintenancePolicy.ACTIVITY));
            try {
                startActivity(managed);
                Toast.makeText(this, "열린 홈 패널에서 ‘뒤로 → 관리 → 기존 홈으로 복원’을 선택해 주세요.", Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException absent) {
                Toast.makeText(this, "독립 설치된 홈 패널이 없습니다.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        new AlertDialog.Builder(this).setTitle("기존 홈으로 복원")
                .setMessage("저장해 둔 기본 홈 설정을 복원하고 자동 시작과 원격 업데이트를 중지합니다.")
                .setPositiveButton("복원", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        MaintenanceService.restore(AFactoryModeMainActivity.this);
                        Toast.makeText(AFactoryModeMainActivity.this, "기존 홈 설정을 복원합니다.", Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("취소", null).show();
    }

    private void startKtApkExport() {
        startKtApkExport(false);
    }

    private void startKtApkExport(boolean homeApp) {
        String appName = homeApp ? "기가지니 홈 앱" : "KT 앱";
        ExportCallback callback = new ExportCallback(this, appName + " 분석 파일");
        boolean started = homeApp ? KtApkExport.startHomeApp(getApplicationContext(), callback)
                : KtApkExport.start(getApplicationContext(), callback);
        showCollectionStart(started, appName);
    }

    private void startSettingsApkExport() {
        String appName = "USB·ADB";
        ExportCallback callback = new ExportCallback(this, appName + " 분석 파일");
        showCollectionStart(KtApkExport.startSettingsApp(getApplicationContext(), callback), appName);
    }

    private void startNetworkAdb() {
        ExportCallback callback = new ExportCallback(this, "네트워크 ADB 연결 시험");
        boolean started = KtApkExport.startNetworkAdb(getApplicationContext(), callback);
        Toast.makeText(this, started
                ? "Mac 수신기를 확인한 뒤 ADB 연결 설정을 시험합니다."
                : "이미 시험이나 파일 전송이 진행 중입니다.", Toast.LENGTH_LONG).show();
    }

    private void startServiceInspection() {
        ExportCallback callback = new ExportCallback(this, "ADB 실행 환경 확인");
        boolean started = KtApkExport.startServiceInspection(getApplicationContext(), callback);
        Toast.makeText(this, started
                ? "실행 권한과 시작 설정을 조회합니다. 임시 결과 값을 기록한 뒤 비웁니다."
                : "이미 시험이나 파일 전송이 진행 중입니다.", Toast.LENGTH_LONG).show();
    }

    private void showCollectionStart(boolean started, String appName) {
        if (started) {
            Toast.makeText(this, appName
                    + " 파일을 읽어 전송합니다. 최대 150초 후 결과를 표시합니다.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "이미 파일을 전송 중이거나 수집을 시작할 수 없습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void startSystemBackup() {
        ExportCallback callback = new ExportCallback(this, "시스템 구성·백업");
        boolean started = KtApkExport.startSystemBackup(getApplicationContext(), callback);
        Toast.makeText(this, started ? "시스템 구성을 Mac에 보내고 백업 계획을 기다립니다. 완료될 때까지 전원을 유지해 주세요."
                : "이미 진단이나 백업이 진행 중입니다.", Toast.LENGTH_LONG).show();
    }

    private void startSpiIdentity() {
        ExportCallback callback = new ExportCallback(this, "SPI 칩 식별");
        boolean started = KtApkExport.startSpiIdentity(getApplicationContext(), callback);
        Toast.makeText(this, started ? "플래시 칩 ID와 상태를 조회합니다. 완료될 때까지 전원을 유지해 주세요."
                : "이미 진단이나 백업이 진행 중입니다.", Toast.LENGTH_LONG).show();
    }

    private void startSpiBackup() {
        ExportCallback callback = new ExportCallback(this, "SPI 4MiB 백업");
        boolean started = KtApkExport.startSpiBackup(getApplicationContext(), callback);
        Toast.makeText(this, started ? "SPI 데이터를 두 번 읽어 비교합니다. 몇 분 걸릴 수 있습니다. 완료될 때까지 전원을 유지해 주세요."
                : "이미 진단이나 백업이 진행 중입니다.", Toast.LENGTH_LONG).show();
    }

    private void startSpiSamples() {
        ExportCallback callback = new ExportCallback(this, "SPI 읽기 진단");
        boolean started = KtApkExport.startSpiSamples(getApplicationContext(), callback);
        Toast.makeText(this, started ? "주소별 작은 샘플을 비교합니다. 완료될 때까지 전원을 유지해 주세요."
                : "이미 진단이나 백업이 진행 중입니다.", Toast.LENGTH_LONG).show();
    }

    private static final class ExportCallback implements KtApkExport.Callback {
        private final WeakReference<AFactoryModeMainActivity> owner;
        private final String title;
        ExportCallback(AFactoryModeMainActivity activity, String title) {
            owner = new WeakReference<AFactoryModeMainActivity>(activity);
            this.title = title;
        }
        @Override public void complete(final KtApkExport.Result result) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    AFactoryModeMainActivity activity = owner.get();
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                    activity.pendingCollectionResult = result;
                    activity.pendingCollectionTitle = title;
                    activity.showCollectionResult();
                }
            });
        }
    }

    private void showCollectionResult() {
        if (!foreground || isFinishing() || isDestroyed() || pendingCollectionResult == null) return;
        if (collectionDialog != null) collectionDialog.dismiss();
        KtApkExport.Result result = pendingCollectionResult;
        pendingCollectionResult = null;
        ScrollView scroll = new ScrollView(this);
        TextView text = new TextView(this);
        text.setText(result.message + "\n\n" + result.diagnostics);
        text.setTextSize(18);
        text.setPadding(dp(24), dp(16), dp(24), dp(16));
        scroll.addView(text, new ScrollView.LayoutParams(-1, -2));
        collectionDialog = new AlertDialog.Builder(this).setTitle(pendingCollectionTitle)
                .setView(scroll).setPositiveButton("닫기", null).create();
        collectionDialog.show();
    }

    private boolean canOpenHomeChooser(Intent chooser) {
        try {
            PackageManager manager = getPackageManager();
            ActivityInfo info = manager.getActivityInfo(chooser.getComponent(), 0);
            return info.enabled && info.exported && info.applicationInfo != null && info.applicationInfo.enabled
                    && (info.permission == null || info.permission.length() == 0
                        || manager.checkPermission(info.permission, getPackageName()) == PackageManager.PERMISSION_GRANTED);
        } catch (PackageManager.NameNotFoundException error) { return false; }
        catch (SecurityException error) { return false; }
    }

    private void showHomeChooserUnavailable() {
        new AlertDialog.Builder(this).setTitle("홈 앱 선택창을 열 수 없습니다")
                .setMessage("이 기기에서는 선택창을 사용할 수 없어요. 기존 기본 홈 설정을 열 수 있습니다.")
                .setPositiveButton("기본 홈 설정", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        openSettings(Settings.ACTION_HOME_SETTINGS);
                    }
                }).setNegativeButton("닫기", null).show();
    }

    private void openSettings(String action) {
        try {
            Intent intent = new Intent(action);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                return;
            }
        } catch (ActivityNotFoundException error) { }
        catch (SecurityException error) { }
        Toast.makeText(this, "이 설정 화면을 열 수 없습니다.", Toast.LENGTH_LONG).show();
    }

    private Button addButton(LinearLayout parent, String label, final Runnable action) {
        Button button = new Button(this);
        button.setText(label); button.setTextSize(20); button.setMinHeight(dp(58));
        button.setId(View.generateViewId());
        button.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { action.run(); } });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layout.topMargin = dp(6);
        parent.addView(button, layout);
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
