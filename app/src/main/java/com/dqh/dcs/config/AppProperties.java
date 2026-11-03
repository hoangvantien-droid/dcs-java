package com.dqh.dcs.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * config/AppProperties — đọc + kiểm tra biến môi trường, fail-fast khi khởi động
 * (port src/config.ts, DCS-8 §6.1).
 *
 * Nguyên tắc: kiểm tra NGAY khi khởi động, dừng hẳn nếu thiếu. Không để hệ thống
 * chạy được rồi hỏng ở request thứ một trăm. Lỗi được gom một lượt rồi báo cùng lúc,
 * thay vì dừng ở lỗi đầu tiên.
 *
 * Bean khởi tạo sớm (constructor ném lỗi) để chặn khởi động ApplicationContext.
 */
@Component
public class AppProperties {

    private final String databaseUrl;
    private final String sessionSecret;
    private final String fileStoragePath;
    private final int dbPoolMax;
    /** Tiền tố đường dẫn khi chạy sau proxy dưới path con (vd /dcs). Rỗng = chạy ở root. */
    private final String basePath;
    /** URL gốc external (https://tienhv.io.vn) cho QR code. */
    private final String publicUrl;
    /** Thiếu thì tắt hỏi đáp AI, phần còn lại vẫn chạy (DCS-8 §6.1). */
    private final String embeddingApiUrl;
    private final String embeddingApiKey;
    private final String tesseractPath;
    /** Tên engine OCR dùng để nhận dạng ký tự; mặc định 'tesseract'. */
    private final String ocrEngine;

    public AppProperties(Environment env) {
        List<String> problems = new ArrayList<>();

        String databaseUrl = trimToEmpty(env.getProperty("DATABASE_URL"));
        if (databaseUrl.isEmpty()) {
            problems.add("Thiếu biến bắt buộc DATABASE_URL");
        }

        String sessionSecret = trimToEmpty(env.getProperty("SESSION_SECRET"));
        if (sessionSecret.isEmpty()) {
            problems.add("Thiếu biến bắt buộc SESSION_SECRET");
        } else if (sessionSecret.length() < 32) {
            problems.add("SESSION_SECRET phải dài tối thiểu 32 ký tự");
        }

        String fileStoragePath = trimToEmpty(env.getProperty("FILE_STORAGE_PATH"));
        if (fileStoragePath.isEmpty()) {
            problems.add("Thiếu biến bắt buộc FILE_STORAGE_PATH");
        }

        int dbPoolMax = 10;
        String rawPoolMax = env.getProperty("DB_POOL_MAX");
        if (rawPoolMax != null && !rawPoolMax.trim().isEmpty()) {
            Integer parsed = parsePositiveInt(rawPoolMax.trim());
            if (parsed == null) {
                problems.add("DB_POOL_MAX không hợp lệ: " + rawPoolMax);
            } else {
                dbPoolMax = parsed;
            }
        }

        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }

        // BASE_PATH: chuẩn hoá về dạng "" hoặc "/dcs" (bỏ dấu / cuối).
        String basePath = trimToEmpty(env.getProperty("BASE_PATH"));
        if (!basePath.isEmpty() && !basePath.startsWith("/")) {
            basePath = "/" + basePath;
        }
        if (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }

        this.databaseUrl = databaseUrl;
        this.sessionSecret = sessionSecret;
        this.fileStoragePath = fileStoragePath;
        this.dbPoolMax = dbPoolMax;
        this.basePath = basePath;
        this.publicUrl = trimToEmpty(env.getProperty("PUBLIC_URL"));
        this.embeddingApiUrl = emptyToNull(env.getProperty("EMBEDDING_API_URL"));
        this.embeddingApiKey = emptyToNull(env.getProperty("EMBEDDING_API_KEY"));
        this.tesseractPath = orDefault(env.getProperty("TESSERACT_PATH"), "/usr/bin/tesseract");
        this.ocrEngine = orDefault(env.getProperty("OCR_ENGINE"), "tesseract");
    }

    private static String trimToEmpty(String v) {
        return v == null ? "" : v.trim();
    }

    private static String emptyToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String orDefault(String v, String fallback) {
        String t = emptyToNull(v);
        return t == null ? fallback : t;
    }

    private static Integer parsePositiveInt(String raw) {
        try {
            int parsed = Integer.parseInt(raw);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String getDatabaseUrl() {
        return databaseUrl;
    }

    public String getSessionSecret() {
        return sessionSecret;
    }

    public String getFileStoragePath() {
        return fileStoragePath;
    }

    public int getDbPoolMax() {
        return dbPoolMax;
    }

    public String getBasePath() {
        return basePath;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public String getEmbeddingApiUrl() {
        return embeddingApiUrl;
    }

    public String getEmbeddingApiKey() {
        return embeddingApiKey;
    }

    public String getTesseractPath() {
        return tesseractPath;
    }

    public String getOcrEngine() {
        return ocrEngine;
    }

    /** Tập hợp lỗi cấu hình rồi báo một lượt, thay vì dừng ở lỗi đầu tiên. */
    public static class ConfigException extends RuntimeException {

        private final transient List<String> problems;

        public ConfigException(List<String> problems) {
            super("Cấu hình không hợp lệ — hệ thống dừng khởi động:\n"
                    + String.join("\n", problems.stream().map(p -> "  - " + p).toList()));
            this.problems = List.copyOf(problems);
        }

        public List<String> getProblems() {
            return problems;
        }
    }
}
