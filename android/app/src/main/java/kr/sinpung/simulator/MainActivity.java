package kr.sinpung.simulator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/site/index.html";
    private static final long RECHECK_MS = 60 * 60 * 1000L; // 앱을 다시 볼 때 1시간 지났으면 업데이트 확인

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ContentStore store;
    private WebView web;
    private LinearLayout overlay;
    private TextView status;
    private ProgressBar bar;
    private Button retry;
    private boolean siteOpen = false, busy = false;
    private long lastCheck = 0, lastBack = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF173E6D);
        store = new ContentStore(this, BuildConfig.CONTENT_BASE);
        buildUi();
        boot();
    }

    // ── 화면 구성 ───────────────────────────────────────────

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF173E6D);

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100); // 휴대폰 글자 크기 설정 때문에 화면 배치가 깨지지 않도록
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUserAgentString(s.getUserAgentString() + " SinpungSimApp/" + BuildConfig.VERSION_NAME);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new LocalClient());
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xFFEEF3F8);
        overlay.setPadding(dp(28), dp(28), dp(28), dp(28));

        TextView title = new TextView(this);
        title.setText("구원GO\nby 신풍승무");
        title.setTextColor(0xFF14263A);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        overlay.addView(title);

        status = new TextView(this);
        status.setTextColor(0xFF4D6075);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(22), 0, dp(14));
        overlay.addView(status);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        overlay.addView(bar, new LinearLayout.LayoutParams(dp(260), dp(16)));

        retry = new Button(this);
        retry.setText("다시 시도");
        retry.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> boot());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        overlay.addView(retry, lp);

        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 안드로이드 15 이상: 화면이 상태바·내비게이션바 밑으로 들어가지 않게 여백 확보
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, bt;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; bt = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); bt = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, bt);
            return insets;
        });
        setContentView(root);
    }

    private void showOverlay(String text, int percent) {
        ui.post(() -> {
            overlay.setVisibility(View.VISIBLE);
            status.setText(text);
            bar.setVisibility(View.VISIBLE);
            bar.setIndeterminate(percent < 0);
            if (percent >= 0) bar.setProgress(percent);
            retry.setVisibility(View.GONE);
        });
    }

    private void showError(String text) {
        ui.post(() -> {
            overlay.setVisibility(View.VISIBLE);
            status.setText(text);
            bar.setVisibility(View.GONE);
            retry.setVisibility(View.VISIBLE);
        });
    }

    private void toast(String t) {
        ui.post(() -> Toast.makeText(this, t, Toast.LENGTH_LONG).show());
    }

    // ── 실행 흐름 ───────────────────────────────────────────

    /** 앱 시작: 최신 목록 확인 → (필요하면 비밀번호) → 바뀐 파일만 받기 → 실행 */
    private void boot() {
        if (busy) return;
        busy = true;
        showOverlay(store.hasContent() ? "최신 내용 확인 중…" : "처음 실행 준비 중…", -1);
        new Thread(() -> {
            JSONObject m;
            try {
                m = store.fetchManifest(store.hasContent() ? 5000 : 15000);
                lastCheck = System.currentTimeMillis();
            } catch (Exception e) {
                busy = false;
                if (store.hasContent()) {
                    ui.post(this::openSite);
                    toast("인터넷에 연결되지 않아 저장된 내용으로 실행합니다.");
                } else {
                    showError("처음 실행할 때는 인터넷 연결이 필요합니다.\n와이파이나 데이터를 켠 뒤 다시 시도해 주세요.");
                }
                return;
            }
            byte[] key = store.savedKey(m);
            if (key == null) {
                ui.post(() -> askPassword(m, null));
            } else {
                update(m, key, false);
            }
        }).start();
    }

    private void askPassword(JSONObject m, String error) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        input.setSingleLine(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(input);
        String msg = (error != null ? error + "\n\n" : "") + "승무원용 비밀번호를 입력해 주세요.\n(한 번만 입력하면 기억됩니다)";
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("비밀번호")
                .setMessage(msg)
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("확인", (dlg, w) -> checkPassword(m, input.getText().toString().trim()))
                .setNegativeButton(store.hasContent() ? "나중에" : "닫기", (dlg, w) -> {
                    busy = false;
                    if (store.hasContent()) openSite(); else finish();
                })
                .create();
        d.show();
        input.requestFocus();
    }

    private void checkPassword(JSONObject m, String pw) {
        if (pw.isEmpty()) { askPassword(m, "비밀번호를 입력하지 않았습니다."); return; }
        showOverlay("비밀번호 확인 중…", -1);
        new Thread(() -> {
            byte[] key = null;
            try { key = store.deriveAndCheck(m, pw); } catch (Exception ignored) { }
            if (key == null) {
                ui.post(() -> askPassword(m, "비밀번호가 맞지 않습니다."));
            } else {
                update(m, key, false);
            }
        }).start();
    }

    /** 백그라운드 스레드에서 호출 */
    private void update(JSONObject m, byte[] key, boolean reloadAfter) {
        boolean had = store.hasContent();
        try {
            if (store.needsUpdate(m)) {
                store.download(m, this::showOverlay);
                showOverlay("준비 중…", -1);
                store.install(m, key, null);
                if (had) {
                    String note = m.optString("note", "");
                    toast("업데이트 완료 (" + m.optString("version") + ")" + (note.isEmpty() ? "" : "\n" + note));
                }
                reloadAfter = reloadAfter || had;
            }
            final boolean reload = reloadAfter;
            ui.post(() -> { if (reload && siteOpen) web.reload(); openSite(); });
        } catch (Exception e) {
            if (had) {
                ui.post(this::openSite);
                toast("업데이트를 받지 못해 저장된 내용으로 실행합니다.");
            } else {
                showError("내려받기에 실패했습니다.\n" + (e.getMessage() == null ? "" : e.getMessage()));
            }
        } finally {
            busy = false;
        }
    }

    private void openSite() {
        overlay.setVisibility(View.GONE);
        if (!siteOpen) {
            siteOpen = true;
            web.loadUrl(START_URL);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        // 오래 켜 둔 상태에서 돌아오면 새 버전이 있는지 조용히 확인
        if (siteOpen && !busy && System.currentTimeMillis() - lastCheck > RECHECK_MS) {
            lastCheck = System.currentTimeMillis();
            new Thread(() -> {
                try {
                    JSONObject m = store.fetchManifest(5000);
                    if (!store.needsUpdate(m)) return;
                    String note = m.optString("note", "");
                    ui.post(() -> new AlertDialog.Builder(this)
                            .setTitle("새 업데이트가 있습니다")
                            .setMessage("버전 " + m.optString("version") + (note.isEmpty() ? "" : "\n" + note) + "\n\n지금 받을까요?")
                            .setPositiveButton("받기", (d, w) -> {
                                busy = true;
                                showOverlay("업데이트 준비 중…", -1);
                                new Thread(() -> {
                                    byte[] key = store.savedKey(m);
                                    if (key == null) { busy = false; ui.post(() -> askPassword(m, null)); }
                                    else update(m, key, true);
                                }).start();
                            })
                            .setNegativeButton("나중에", null)
                            .show());
                } catch (Exception ignored) { }
            }).start();
        }
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (siteOpen && web.canGoBack()) { web.goBack(); return; }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) { super.onBackPressed(); return; }
        lastBack = now;
        Toast.makeText(this, "한 번 더 누르면 종료합니다.", Toast.LENGTH_SHORT).show();
    }

    // ── 휴대폰 안의 파일을 웹 주소처럼 보여주기 ─────────────────

    private class LocalClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (HOST.equals(u.getHost()) || "blob".equals(u.getScheme()) || "data".equals(u.getScheme())) return false;
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || !path.startsWith("/site/")) return notFound();
            try {
                File base = store.siteDir().getCanonicalFile();
                File f = new File(base, path.substring(6)).getCanonicalFile();
                if (!f.getPath().startsWith(base.getPath()) || !f.isFile()) return notFound();
                return serve(f, req.getRequestHeaders());
            } catch (IOException e) {
                return notFound();
            }
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), null);
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");

    /** 영상 재생을 위해 Range(부분 요청)도 처리 */
    private static WebResourceResponse serve(File f, Map<String, String> reqHeaders) throws IOException {
        String type = mime(f.getName());
        String enc = type.startsWith("text/") ? "utf-8" : null;
        long len = f.length();
        Map<String, String> h = new HashMap<>();
        h.put("Accept-Ranges", "bytes");
        h.put("Cache-Control", "no-cache");
        h.put("Access-Control-Allow-Origin", "*");
        String range = null;
        if (reqHeaders != null) for (Map.Entry<String, String> e : reqHeaders.entrySet())
            if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();
        if (range != null) {
            Matcher m = RANGE.matcher(range.trim());
            if (m.matches()) {
                long start = Long.parseLong(m.group(1));
                long end = m.group(2).isEmpty() ? len - 1 : Math.min(Long.parseLong(m.group(2)), len - 1);
                if (start <= end && start < len) {
                    InputStream in = new FileInputStream(f);
                    long skipped = 0;
                    while (skipped < start) { long k = in.skip(start - skipped); if (k <= 0) break; skipped += k; }
                    h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
                    h.put("Content-Length", String.valueOf(end - start + 1));
                    return new WebResourceResponse(type, enc, 206, "Partial Content", h, new Limited(in, end - start + 1));
                }
            }
        }
        h.put("Content-Length", String.valueOf(len));
        return new WebResourceResponse(type, enc, 200, "OK", h, new FileInputStream(f));
    }

    private static class Limited extends FilterInputStream {
        private long left;
        Limited(InputStream in, long n) { super(in); left = n; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int c = super.read();
            if (c >= 0) left--;
            return c;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
