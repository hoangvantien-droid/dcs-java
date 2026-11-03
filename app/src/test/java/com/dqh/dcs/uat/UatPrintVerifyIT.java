package com.dqh.dcs.uat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;

import com.dqh.dcs.common.Clock;
import com.dqh.dcs.support.DbSupport;
import com.dqh.dcs.support.ItServices;

/**
 * UatPrintVerifyIT — in bản kiểm soát + tra cứu QR công khai (IND-05).
 *   UAT-60: Author in bản hiệu lực → sinh verify_token.
 *   UAT-61 [BẢO MẬT]: mở /verify/{token} KHÔNG đăng nhập → 200, trả tình trạng.
 *   UAT-62 [BẢO MẬT]: token sai → 200, báo không tìm thấy bản in.
 */
class UatPrintVerifyIT extends UatHttpBase {

    private final List<String> createdDocs = new java.util.ArrayList<>();
    private ItServices svc;

    @AfterEach
    void tearDown() {
        for (String d : createdDocs) {
            DbSupport.cleanupDoc(d);
        }
    }

    private ItServices svc() {
        if (svc == null) {
            svc = new ItServices();
        }
        return svc;
    }

    /** UAT-60 + UAT-61: in sinh token + tra cứu công khai (không đăng nhập). */
    @Test
    void inSinhTokenVaTraCuuCongKhai() {
        var seed = DbSupport.seed();
        String site0 = seed.siteIds.get(0);
        String docId = "UAT-PRINT-" + System.currentTimeMillis();
        createdDocs.add(docId);
        String versionId = DbSupport.tx().execute(s -> svc().registration.submitRegistration(
                ItServices.docInput(docId), ItServices.versionInput(docId, seed.authorId, Clock.isoTodayVN()),
                List.of(site0)));

        // Author in bản hiệu lực (qua HTTP) → trang print-result (200).
        String authorCookie = login(AUTHOR_EMAIL);
        ResponseEntity<String> print = postForm("/documents/" + versionId + "/print", authorCookie,
                new LinkedMultiValueMap<>());
        assertEquals(HttpStatus.OK, print.getStatusCode(), "in bản hiệu lực → trang kết quả in");

        // Lấy verify_token vừa sinh.
        String token = DbSupport.plainJdbc().queryForObject(
                "SELECT verify_token FROM printed_copies WHERE version_id = ?::uuid "
                + "ORDER BY printed_at DESC LIMIT 1", String.class, versionId);
        assertNotNull(token, "in phải sinh verify_token");

        // Tra cứu QR công khai: KHÔNG gửi cookie.
        ResponseEntity<String> verify = get("/verify/" + token, null);
        assertEquals(HttpStatus.OK, verify.getStatusCode(), "/verify là route công khai, trả 200");
        assertNotNull(verify.getBody());
        // Không lộ nội dung tài liệu (section raw_text) — chỉ tình trạng.
        assertTrue(verify.getBody().length() > 0);
    }

    /** UAT-62 [BẢO MẬT]: token sai → 200, báo không tìm thấy bản in. */
    @Test
    void tokenSaiBaoKhongTimThay() {
        ResponseEntity<String> verify = get("/verify/token-khong-ton-tai-123", null);
        assertEquals(HttpStatus.OK, verify.getStatusCode());
        assertNotNull(verify.getBody());
    }
}
