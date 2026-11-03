package com.dqh.dcs.uat;

import java.net.URI;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.dqh.dcs.support.DbSupport;

/**
 * uat/UatHttpBase — nền chung cho các test UAT end-to-end chạy qua HTTP THẬT
 * (@SpringBootTest webEnvironment=RANDOM_PORT + TestRestTemplate).
 *
 * MẤU CHỐT: Spring Boot phải nối CÙNG cơ sở dữ liệu mà DbSupport đã migrate +
 * seed (28 .sql + tài khoản author/reader/admin/dc_*). @DynamicPropertySource
 * lấy URL/username/password đã phân giải từ DbSupport rồi bơm vào
 * spring.datasource.* cùng SESSION_SECRET/FILE_STORAGE_PATH bắt buộc.
 *
 * Tự-SKIP: Assumptions.assumeTrue(DbSupport.dbOk()) ở @BeforeEach (cùng khuôn
 * với các *IT hiện có). Máy KHÔNG có Docker/DB → mọi test skip; context vẫn khởi
 * được nhờ initialization-fail-timeout=-1 (Hikari nối lười) và test dừng TRƯỚC
 * khi gọi HTTP nên không chạm DB — build không fail. Trên CI có Postgres
 * (DATABASE_URL_TEST) thì dbOk()=true → CHẠY THẬT, không skip, vượt guard
 * "skip=0" của CI.
 *
 * Mật khẩu seed dùng để đăng nhập HTTP: 'matkhau-test-123' (hash argon2id tĩnh
 * trong DbSupport khớp mật khẩu này — xác định qua Argon2VerifyTest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class UatHttpBase {

    protected static final String PASSWORD = "matkhau-test-123";

    protected static final String AUTHOR_EMAIL = "author@mml.io";
    protected static final String READER_EMAIL = "reader@mml.io";
    protected static final String ADMIN_EMAIL = "admin@mml.io";
    protected static final String DC_MML_EMAIL = "dc_mml@mml.io";
    protected static final String DC_HCM_EMAIL = "dc_hcm@mml.io";

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    /**
     * SELF-SKIP sạch khi KHÔNG có DB (máy build không-Docker): chỉ dựa dbOk().
     * Trên CI có Postgres (DATABASE_URL_TEST) thì dbOk()=true → KHÔNG skip, chạy
     * THẬT (vượt guard "skip=0" của CI). ApplicationContext vẫn khởi được nhờ
     * initialization-fail-timeout=-1 (Hikari nối lười, không nối lúc khởi động),
     * và test tự dừng TRƯỚC khi gọi HTTP nên không chạm DB.
     */
    @BeforeEach
    void requireDb() {
        Assumptions.assumeTrue(DbSupport.dbOk(), "Không có Docker/DB — bỏ qua UAT IT");
        // Dùng JDK HttpClient (KHÔNG phải HttpURLConnection). Lý do: khi server
        // trả 401 KÈM WWW-Authenticate (đăng nhập sai, /mcp thiếu cookie),
        // HttpURLConnection dưới SimpleClientHttpRequestFactory TỰ ĐỘNG thử lại
        // xác thực và ném HttpRetryException ('cannot retry ... in streaming
        // mode') thay vì trả status cho test. JDK HttpClient KHÔNG tự xác thực
        // khi không gán Authenticator, nên đọc 401 bình thường. Redirect.NEVER
        // giữ nguyên hành vi đọc 302 + Set-Cookie mà test đang dựa vào.
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory f =
                new org.springframework.http.client.JdkClientHttpRequestFactory(client);
        f.setReadTimeout(java.time.Duration.ofSeconds(30));
        rest.getRestTemplate().setRequestFactory(f);
    }

    /**
     * Bơm cấu hình để Spring Boot nối đúng DB DbSupport đã dựng + các biến bắt
     * buộc cho AppProperties. Khi không có DB, dùng URL giả + Hikari nối lười để
     * context vẫn khởi (test sẽ tự skip ở @BeforeEach).
     */
    @DynamicPropertySource
    static void springProps(DynamicPropertyRegistry registry) {
        // Kích hoạt DbSupport (migrate + seed) và lấy thông số kết nối.
        boolean ok = DbSupport.dbOk();
        String url = ok ? DbSupport.jdbcUrl() : "jdbc:postgresql://localhost:5432/unused";
        registry.add("DATABASE_URL", () -> url);
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> ok ? nz(DbSupport.username()) : "unused");
        registry.add("spring.datasource.password", () -> ok ? nz(DbSupport.password()) : "unused");
        registry.add("SESSION_SECRET", () -> "uat-session-secret-0123456789-abcdefgh");
        registry.add("FILE_STORAGE_PATH", UatHttpBase::tmpStorage);
        registry.add("PUBLIC_URL", () -> "http://localhost");
        registry.add("DB_USER", () -> ok ? nz(DbSupport.username()) : "unused");
        registry.add("DB_PASS", () -> ok ? nz(DbSupport.password()) : "unused");
        // Không có DB: Hikari nối LƯỜI, không fail lúc khởi context (test tự skip).
        registry.add("spring.datasource.hikari.initialization-fail-timeout", () -> ok ? "1" : "-1");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String tmpStorage() {
        try {
            return Files.createTempDirectory("dcs-uat-storage").toString();
        } catch (Exception e) {
            return System.getProperty("java.io.tmpdir");
        }
    }

    // === tiện ích HTTP ===

    private String base() {
        return "http://localhost:" + port;
    }

    /**
     * TestRestTemplate KHÔNG tự theo redirect — mặc định của TestRestTemplate
     * (Spring Boot 3.2) vô hiệu hoá theo-redirect, nên đọc được 302 + Set-Cookie.
     */
    protected TestRestTemplate noRedirect() {
        return rest;
    }

    /**
     * Đăng nhập qua HTTP THẬT, trả về cookie 'dcs_sid' đã ký (giá trị cookie,
     * không kèm thuộc tính). Ném AssertionError nếu không nhận được cookie.
     */
    protected String login(String email) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("email", email);
        form.add("password", PASSWORD);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> resp = noRedirect().postForEntity(
                base() + "/login", new HttpEntity<>(form, headers), String.class);
        String cookie = extractSessionCookie(resp.getHeaders());
        if (cookie == null) {
            throw new AssertionError("Đăng nhập thất bại cho " + email
                    + " (status=" + resp.getStatusCode() + ") — không nhận được cookie dcs_sid");
        }
        return cookie;
    }

    /** Lấy phần `dcs_sid=...` từ các header Set-Cookie (bỏ thuộc tính Path/HttpOnly...). */
    protected static String extractSessionCookie(HttpHeaders headers) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return null;
        }
        for (String sc : setCookies) {
            String first = sc.split(";", 2)[0].trim();
            if (first.startsWith("dcs_sid=")) {
                String val = first.substring("dcs_sid=".length());
                if (!val.isEmpty()) {
                    return first; // trả dạng 'dcs_sid=<signed>'
                }
            }
        }
        return null;
    }

    protected HttpHeaders cookie(String sessionCookie) {
        HttpHeaders h = new HttpHeaders();
        if (sessionCookie != null) {
            h.add(HttpHeaders.COOKIE, sessionCookie);
        }
        return h;
    }

    protected HttpHeaders formHeaders(String sessionCookie) {
        HttpHeaders h = cookie(sessionCookie);
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return h;
    }

    protected HttpHeaders jsonHeaders(String sessionCookie) {
        HttpHeaders h = cookie(sessionCookie);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /** GET có cookie, không theo redirect. */
    protected ResponseEntity<String> get(String path, String sessionCookie) {
        return noRedirect().exchange(base() + path, HttpMethod.GET,
                new HttpEntity<>(cookie(sessionCookie)), String.class);
    }

    /** POST form urlencoded có cookie, không theo redirect. */
    protected ResponseEntity<String> postForm(String path, String sessionCookie,
            MultiValueMap<String, String> form) {
        return noRedirect().postForEntity(base() + path,
                new HttpEntity<>(form, formHeaders(sessionCookie)), String.class);
    }

    /** POST JSON có cookie (dùng cho /mcp). */
    protected ResponseEntity<String> postJson(String path, String sessionCookie, String json) {
        return noRedirect().postForEntity(base() + path,
                new HttpEntity<>(json, jsonHeaders(sessionCookie)), String.class);
    }

    protected static URI uri(String s) {
        return URI.create(s);
    }
}
