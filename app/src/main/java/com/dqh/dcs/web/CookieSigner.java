package com.dqh.dcs.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.dqh.dcs.config.AppProperties;

/**
 * web/CookieSigner — ký/kiểm cookie theo đúng định dạng node 'cookie-signature'
 * mà @fastify/cookie dùng mặc định (QĐ-E).
 *
 * Định dạng: value + '.' + base64(HMAC_SHA256(value, secret)) với dấu '=' đuôi
 * bị cắt. Unsign tính lại chữ ký và so sánh theo thời gian hằng. Giữ nguyên
 * thuật toán để verify được cookie Node cũ sinh bằng cùng SESSION_SECRET
 * (Lệch#1: nếu secret đổi thì phiên cũ không mang sang — đăng nhập lại 1 lần).
 */
@Component
public class CookieSigner {

    private final byte[] secret;

    public CookieSigner(AppProperties props) {
        this.secret = props.getSessionSecret().getBytes(StandardCharsets.UTF_8);
    }

    /** Ký một giá trị -> 'value.signature'. */
    public String sign(String value) {
        return value + "." + hmac(value);
    }

    /**
     * Kiểm chữ ký cookie đã ký. Trả về value gốc nếu hợp lệ, null nếu không
     * (thiếu dấu chấm, chữ ký sai).
     */
    public String unsign(String signed) {
        if (signed == null) {
            return null;
        }
        int dot = signed.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String value = signed.substring(0, dot);
        String mac = signed.substring(dot + 1);
        String expected = hmac(value);
        byte[] a = mac.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) {
            return null;
        }
        return value;
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            // base64 chuẩn, cắt '=' đuôi (khớp node cookie-signature).
            String b64 = Base64.getEncoder().encodeToString(raw);
            int end = b64.length();
            while (end > 0 && b64.charAt(end - 1) == '=') {
                end--;
            }
            return b64.substring(0, end);
        } catch (Exception e) {
            throw new IllegalStateException("Không ký được cookie phiên", e);
        }
    }
}
