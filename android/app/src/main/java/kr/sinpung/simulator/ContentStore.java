package kr.sinpung.simulator;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * GitHub Pages에 올라간 암호화 콘텐츠를 내려받아 휴대폰 안에 풀어 두는 저장소.
 *
 *  files/enc/<id>.bin   : 받은 암호문(바뀐 파일만 새로 받음)
 *  files/site/...       : 풀어 둔 현재 버전(WebView가 여기서 읽음)
 *  files/site.json      : 현재 버전의 목록(manifest)
 */
public class ContentStore {

    public interface Progress {
        void onProgress(String text, int percent);
    }

    private static final String CHECK_TEXT = "SINPUNG-SIM-OK";
    private static final String PREF = "sim";

    private final String base;
    private final File root, encDir, siteDir, siteJson;
    private final SharedPreferences prefs;

    public ContentStore(Context ctx, String base) {
        this.base = base.endsWith("/") ? base : base + "/";
        root = ctx.getFilesDir();
        encDir = new File(root, "enc");
        siteDir = new File(root, "site");
        siteJson = new File(root, "site.json");
        prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        encDir.mkdirs();
    }

    public File siteDir() { return siteDir; }

    public boolean hasContent() {
        return siteJson.exists() && new File(siteDir, "index.html").exists();
    }

    public String currentVersion() {
        try { return readManifest(siteJson).optString("version", ""); } catch (Exception e) { return ""; }
    }

    // ── 네트워크 ──────────────────────────────────────────────

    public JSONObject fetchManifest(int timeoutMs) throws Exception {
        byte[] b = httpGet(base + "manifest.json?t=" + System.currentTimeMillis(), timeoutMs, null);
        return new JSONObject(new String(b, StandardCharsets.UTF_8));
    }

    private byte[] httpGet(String url, int timeoutMs, Progress p) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(Math.max(timeoutMs, 20000));
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(c.getContentLength(), 16384));
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    // ── 업데이트 판단 / 다운로드 / 설치 ───────────────────────

    public boolean needsUpdate(JSONObject m) {
        if (!hasContent()) return true;
        try {
            JSONObject cur = readManifest(siteJson);
            return !cur.optString("version").equals(m.optString("version"))
                    || !cur.getJSONArray("files").toString().equals(m.getJSONArray("files").toString());
        } catch (Exception e) {
            return true;
        }
    }

    /** 새로 받아야 하는 용량(바이트) */
    public long downloadSize(JSONObject m) throws Exception {
        long sum = 0;
        JSONArray files = m.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject e = files.getJSONObject(i);
            File f = new File(encDir, e.getString("id") + ".bin");
            if (!f.exists() || f.length() != e.getLong("size")) sum += e.getLong("size");
        }
        return sum;
    }

    public void download(JSONObject m, Progress p) throws Exception {
        JSONArray files = m.getJSONArray("files");
        long total = Math.max(1, downloadSize(m)), done = 0;
        for (int i = 0; i < files.length(); i++) {
            JSONObject e = files.getJSONObject(i);
            String id = safeId(e.getString("id"));
            File f = new File(encDir, id + ".bin");
            if (f.exists() && f.length() == e.getLong("size")) continue;
            if (p != null) p.onProgress(String.format("내려받는 중 (%.1f / %.1f MB)", done / 1e6, total / 1e6), (int) (done * 100 / total));
            byte[] data = null;
            for (int tryNo = 0; tryNo < 3 && data == null; tryNo++) {
                try { data = httpGet(base + "f/" + id + ".bin", 15000, null); }
                catch (IOException ex) { if (tryNo == 2) throw ex; }
            }
            if (!hex(sha256(data)).equals(e.getString("sha256"))) throw new IOException("파일이 손상되었습니다. 다시 시도해 주세요.");
            File tmp = new File(encDir, id + ".tmp");
            writeFile(tmp, data);
            if (!tmp.renameTo(f)) throw new IOException("저장 실패");
            done += e.getLong("size");
        }
        if (p != null) p.onProgress("내려받기 완료", 100);
    }

    /** 받은 암호문을 풀어서 site 폴더를 새 버전으로 교체 */
    public void install(JSONObject m, byte[] key, Progress p) throws Exception {
        File fresh = new File(root, "site_new");
        File old = new File(root, "site_old");
        deleteTree(fresh);
        deleteTree(old);
        JSONArray files = m.getJSONArray("files");
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject e = files.getJSONObject(i);
            String id = safeId(e.getString("id"));
            keep.add(id + ".bin");
            String path = e.getString("path");
            if (path.contains("..") || path.startsWith("/")) throw new IOException("잘못된 경로");
            byte[] plain = decrypt(key, readFile(new File(encDir, id + ".bin")));
            File out = new File(fresh, path);
            out.getParentFile().mkdirs();
            writeFile(out, plain);
            if (p != null) p.onProgress("준비 중…", (i + 1) * 100 / files.length());
        }
        if (siteDir.exists() && !siteDir.renameTo(old)) deleteTree(siteDir);
        if (!fresh.renameTo(siteDir)) throw new IOException("설치 실패");
        writeFile(siteJson, m.toString().getBytes(StandardCharsets.UTF_8));
        deleteTree(old);
        File[] all = encDir.listFiles();
        if (all != null) for (File f : all) if (!keep.contains(f.getName())) f.delete();
    }

    // ── 비밀번호 / 키 ───────────────────────────────────────

    /** 저장된 키가 이 manifest와 맞으면 돌려주고, 아니면 null */
    public byte[] savedKey(JSONObject m) {
        try {
            String salt = m.getJSONObject("kdf").getString("salt");
            if (!salt.equals(prefs.getString("salt", ""))) return null;
            byte[] key = Base64.decode(prefs.getString("key", ""), Base64.NO_WRAP);
            return checkKey(m, key) ? key : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 오프라인으로 열 때: 현재 설치된 버전 기준으로 저장된 키 확인 */
    public boolean hasSavedKeyForCurrent() {
        try { return savedKey(readManifest(siteJson)) != null; } catch (Exception e) { return false; }
    }

    public byte[] deriveAndCheck(JSONObject m, String password) throws Exception {
        JSONObject kdf = m.getJSONObject("kdf");
        byte[] salt = Base64.decode(kdf.getString("salt"), Base64.NO_WRAP);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] bits = f.generateSecret(new PBEKeySpec(password.toCharArray(), salt, kdf.getInt("iter"), 512)).getEncoded();
        byte[] key = Arrays.copyOf(bits, 32);
        if (!checkKey(m, key)) return null;
        prefs.edit().putString("salt", kdf.getString("salt")).putString("key", Base64.encodeToString(key, Base64.NO_WRAP)).apply();
        return key;
    }

    public void forgetKey() { prefs.edit().remove("salt").remove("key").apply(); }

    private boolean checkKey(JSONObject m, byte[] key) {
        try {
            byte[] plain = decrypt(key, Base64.decode(m.getString("check"), Base64.NO_WRAP));
            return CHECK_TEXT.equals(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] decrypt(byte[] key, byte[] blob) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, blob, 0, 12));
        return c.doFinal(blob, 12, blob.length - 12);
    }

    // ── 도우미 ─────────────────────────────────────────────

    private static String safeId(String id) throws IOException {
        if (!id.matches("[0-9a-f]{8,64}")) throw new IOException("잘못된 파일 이름");
        return id;
    }

    private static JSONObject readManifest(File f) throws Exception {
        return new JSONObject(new String(readFile(f), StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] b) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(b);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(f.length(), 1024));
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
