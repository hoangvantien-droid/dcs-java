package com.dqh.dcs.jobs;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * jobs/OcrWorker — trình xử lý nội dung theo lô, chạy RỜI (port src/jobs/ocr-worker.ts, T-046).
 *
 * Kích hoạt bởi profile 'ocr': chạy như tiến trình ONESHOT qua systemd
 * ExecStartPost (deploy/dcs-jobs.service), MỖI LẦN MỘT TỆP rồi thoát để trả bộ
 * nhớ (BATCH_LIMIT=1 trên máy 1 GB, DCS-5 §1.3). Tách khỏi {@link DailyJobsRunner}
 * (profile 'jobs') đúng như hai tệp entry rời của bản TS.
 *
 * Xử lý các phiên bản chưa bóc tách (parse_status='chua_chay').
 *
 * ĐIỂM MỞ GĐ2: tầng domain content/ocr (processVersionContent qua PDFBox + OCR
 * tesseract) được FEAT-002 để lại GĐ sau (best-effort, KHÔNG chặn ban hành). Khi
 * tầng đó được port, nối lời gọi vào vòng lặp dưới đây (contentService.process(
 * versionId)); hợp đồng oneshot-mỗi-lần-một-tệp giữ nguyên.
 */
@Component
@Profile("ocr")
public class OcrWorker implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(OcrWorker.class);

    /** Mỗi lần một tệp trên máy 1 GB (BATCH_LIMIT của bản TS). */
    private static final int BATCH_LIMIT = 1;

    private final ApplicationContext ctx;
    private final NamedParameterJdbcTemplate jdbc;

    public OcrWorker(ApplicationContext ctx, NamedParameterJdbcTemplate jdbc) {
        this.ctx = ctx;
        this.jdbc = jdbc;
    }

    /**
     * Lấy (tối đa BATCH_LIMIT) phiên bản chưa bóc tách rồi xử lý nội dung. Trả về
     * danh sách version_id đã xử lý. Tách khỏi run() để test gọi trực tiếp.
     */
    public List<String> runOcrBatch() {
        List<String> pending = jdbc.query(
                "SELECT version_id FROM document_versions "
                + "WHERE parse_status = 'chua_chay' "
                + "ORDER BY submitted_at NULLS LAST "
                + "LIMIT :limit",
                new MapSqlParameterSource("limit", BATCH_LIMIT),
                (rs, i) -> rs.getString("version_id"));

        for (String versionId : pending) {
            // ĐIỂM MỞ GĐ2: nối processVersionContent khi domain content/ocr được port.
            log.info("Phiên bản chờ bóc tách: {} — domain content/ocr để lại Điểm mở GĐ2", versionId);
        }
        return pending;
    }

    @Override
    public void run(String... args) {
        int code = 0;
        try {
            List<String> processed = runOcrBatch();
            log.info("OCR/bóc tách xong: {} tệp", processed.size());
        } catch (RuntimeException err) {
            log.error("OCR worker lỗi", err);
            code = 1;
        }
        final int exitCode = code;
        System.exit(SpringApplication.exit(ctx, () -> exitCode));
    }
}
