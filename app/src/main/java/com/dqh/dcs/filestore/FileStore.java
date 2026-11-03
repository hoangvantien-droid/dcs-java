package com.dqh.dcs.filestore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.dqh.dcs.config.AppProperties;

/**
 * filestore/FileStore — lưu tệp PDF bất biến theo năm/tháng/uuid.pdf + SHA-256
 * (port src/domain/file-store.ts). Giữ UTC cho đường dẫn; ghi CREATE_NEW (bất
 * biến). KHÔNG sửa tệp gốc (nguyên tắc số 2).
 */
@Component
public class FileStore {

    private final String root;

    public FileStore(AppProperties props) {
        this.root = props.getFileStoragePath();
    }

    public record Stored(String filePath, String fileHash) {
    }

    /** Ghi một tệp mới; trả về đường dẫn tuyệt đối + hash SHA-256. */
    public Stored storeFile(byte[] content, String originalName) throws IOException {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String year = String.format("%04d", now.getYear());
        String month = String.format("%02d", now.getMonthValue());
        Path dir = Path.of(root, year, month);
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID() + ".pdf");
        Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new Stored(target.toAbsolutePath().toString(), sha256(content));
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Không tính được SHA-256", e);
        }
    }
}
