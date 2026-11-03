package com.dqh.dcs.receipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dqh.dcs.registration.VersionInput;
import com.dqh.dcs.support.DbSupport;
import com.dqh.dcs.support.ItServices;

/**
 * SupersedeIT — ĐIỂM 1: supersede mô hình mới (port
 * test/domain/supersede-lifecycle.integration.test.ts). Bản gốc het_hieu_luc
 * TRƯỚC khi có bản mới, giữ liên kết; uq_one_current_per_doc chặn 2 bản
 * da_ban_hanh/doc_id; kế thừa ack khi 'nho', KHÔNG kế thừa khi 'lon'. Tự SKIP
 * khi không có DB.
 */
class SupersedeIT {

    private ItServices svc;
    private JdbcTemplate db;
    private final List<String> created = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DbSupport.dbOk(), "Không có Docker/DB — bỏ qua IT");
        svc = new ItServices();
        db = DbSupport.plainJdbc();
    }

    @AfterEach
    void tearDown() {
        if (DbSupport.dbOk()) {
            for (String d : created) {
                DbSupport.cleanupDoc(d);
            }
        }
    }

    private String register(String docId, VersionInput v) {
        return DbSupport.tx().execute(s -> svc.registration.submitRegistration(
                ItServices.docInput(docId), v, List.of(DbSupport.seed().siteIds.get(0))));
    }

    private static VersionInput supersedeInput(String docId, String authorId, String versionNo,
            String hash, String supersedesId, String sig, String summary) {
        String today = com.dqh.dcs.common.Clock.isoTodayVN();
        return new VersionInput(docId, versionNo, "LC " + docId, today, null, today, 12, null,
                "thu_cong", "/tmp/lc.pdf", hash, authorId, "", supersedesId, sig, summary);
    }

    @Test
    void banThayTheLamGocHetHieuLucVaGiuLienKet() {
        var seed = DbSupport.seed();
        String docId = "IT-SUP-" + System.currentTimeMillis();
        created.add(docId);
        String origId = register(docId, ItServices.versionInput(docId, seed.authorId, null));
        String newId = register(docId, supersedeInput(docId, seed.authorId, "2.0", "hash-sup-2",
                origId, "nho", null));

        assertEquals("het_hieu_luc", db.queryForObject(
                "SELECT status FROM document_versions WHERE version_id = ?::uuid", String.class, origId));
        assertEquals("da_ban_hanh", db.queryForObject(
                "SELECT status FROM document_versions WHERE version_id = ?::uuid", String.class, newId));
        assertEquals(origId, db.queryForObject(
                "SELECT supersedes_version_id::text FROM document_versions WHERE version_id = ?::uuid",
                String.class, newId));
    }

    @Test
    void uqOneCurrentPerDocChan2BanDaBanHanh() {
        var seed = DbSupport.seed();
        String docId = "IT-SUP2-" + System.currentTimeMillis();
        created.add(docId);
        register(docId, ItServices.versionInput(docId, seed.authorId, null));
        // Chèn bản thứ hai ở cho_hoan_thien rồi cố ép sang da_ban_hanh -> vi phạm index.
        String v2 = DbSupport.tx().execute(s -> db.queryForObject(
                "INSERT INTO document_versions (doc_id, version_no, title, issued_date, effective_date, "
                + "review_cycle_months, source_channel, file_path, file_hash, status, registrant_user_id) "
                + "VALUES (?, '2.0', 'dup current', CURRENT_DATE, CURRENT_DATE, 12, 'thu_cong', "
                + "'/tmp/x.pdf', 'hash-dup', 'cho_hoan_thien', ?::uuid) RETURNING version_id::text",
                String.class, docId, seed.authorId));
        DataAccessException ex = assertThrows(DataAccessException.class,
                () -> DbSupport.tx().execute(s -> db.update(
                        "UPDATE document_versions SET status = 'da_ban_hanh' WHERE version_id = ?::uuid", v2)));
        assertEquals(true, ex.getMessage() != null && ex.getMessage().contains("uq_one_current_per_doc"));
    }

    @Test
    void thayDoiNhoKeThuaAck() {
        var seed = DbSupport.seed();
        String docId = "IT-SUP3-" + System.currentTimeMillis();
        created.add(docId);
        String origId = register(docId, ItServices.versionInput(docId, seed.authorId, null));
        DbSupport.tx().execute(s -> db.update(
                "INSERT INTO read_acknowledgements (version_id, user_id, acknowledged_at) "
                + "VALUES (?::uuid, ?::uuid, now()) ON CONFLICT (version_id, user_id) DO NOTHING",
                origId, seed.authorId));
        String newId = register(docId, supersedeInput(docId, seed.authorId, "2.0", "hash-sup3-2",
                origId, "nho", null));
        Integer cnt = db.queryForObject(
                "SELECT count(*)::int FROM read_acknowledgements WHERE version_id = ?::uuid AND user_id = ?::uuid",
                Integer.class, newId, seed.authorId);
        assertEquals(1, cnt);
        assertEquals(origId, db.queryForObject(
                "SELECT inherited_from_version_id::text FROM read_acknowledgements "
                + "WHERE version_id = ?::uuid AND user_id = ?::uuid", String.class, newId, seed.authorId));
    }

    @Test
    void thayDoiLonKhongKeThuaAck() {
        var seed = DbSupport.seed();
        String docId = "IT-SUP4-" + System.currentTimeMillis();
        created.add(docId);
        String origId = register(docId, ItServices.versionInput(docId, seed.authorId, null));
        DbSupport.tx().execute(s -> db.update(
                "INSERT INTO read_acknowledgements (version_id, user_id, acknowledged_at) "
                + "VALUES (?::uuid, ?::uuid, now()) ON CONFLICT (version_id, user_id) DO NOTHING",
                origId, seed.authorId));
        String newId = register(docId, supersedeInput(docId, seed.authorId, "2.0", "hash-sup4-2",
                origId, "lon", "Đổi quy trình đáng kể"));
        Integer cnt = db.queryForObject(
                "SELECT count(*)::int FROM read_acknowledgements WHERE version_id = ?::uuid",
                Integer.class, newId);
        assertEquals(0, cnt);
    }
}
