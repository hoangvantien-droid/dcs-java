package com.dqh.dcs.acknowledgement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dqh.dcs.support.DbSupport;
import com.dqh.dcs.support.ItServices;

/**
 * AckContextIT — xác nhận đọc lưu BỐI CẢNH tại thời điểm xác nhận (port tinh
 * thần test ack: department_id/functional_group_id ghi lúc ack, không tra lại
 * L1). Dựng distribution_matrix cho Dept chính của Reader (fg NULL) để Reader
 * thuộc diện phải đọc, rồi acknowledge và kiểm department_id đã lưu. Tự SKIP
 * khi không có DB.
 */
class AckContextIT {

    private ItServices svc;
    private String readerId;
    private String readerDeptId;
    private final List<String> created = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DbSupport.dbOk(), "Không có Docker/DB — bỏ qua IT");
        svc = new ItServices();
        var db = DbSupport.plainJdbc();
        var row = db.queryForList(
                "SELECT u.user_id::text AS uid, u.primary_dept_id::text AS did "
                + "FROM users u WHERE u.email = 'reader@mml.io' AND u.primary_dept_id IS NOT NULL LIMIT 1");
        Assumptions.assumeTrue(!row.isEmpty(), "Chưa seed Reader có Dept chính");
        readerId = (String) row.get(0).get("uid");
        readerDeptId = (String) row.get(0).get("did");
    }

    @AfterEach
    void tearDown() {
        if (DbSupport.dbOk()) {
            for (String d : created) {
                DbSupport.cleanupDoc(d);
            }
        }
    }

    @Test
    void acknowledgeLuuDepartmentIdTaiThoiDiemXacNhan() {
        var seed = DbSupport.seed();
        String docId = "IT-ACK-" + System.currentTimeMillis();
        created.add(docId);
        String versionId = DbSupport.tx().execute(s -> svc.registration.submitRegistration(
                ItServices.docInput(docId), ItServices.versionInput(docId, seed.authorId, null),
                List.of(seed.siteIds.get(0))));

        var db = DbSupport.plainJdbc();
        // Ma trận phân phối: Dept chính của Reader phải đọc (fg NULL).
        DbSupport.tx().execute(st -> db.update(
                "INSERT INTO distribution_matrix (doc_id, department_id, functional_group_id, require_ack, created_by) "
                + "VALUES (?, ?::uuid, NULL, TRUE, ?::uuid) ON CONFLICT DO NOTHING",
                docId, readerDeptId, seed.authorId));

        // Reader thuộc diện phải đọc.
        assertTrue(svc.acknowledgement.mustAcknowledge(versionId, readerId));

        DbSupport.tx().execute(st -> {
            svc.acknowledgement.acknowledge(versionId, readerId);
            return null;
        });

        var ack = db.queryForList(
                "SELECT department_id::text AS dept, functional_group_id::text AS fg "
                + "FROM read_acknowledgements WHERE version_id = ?::uuid AND user_id = ?::uuid",
                versionId, readerId);
        assertEquals(1, ack.size());
        assertEquals(readerDeptId, ack.get(0).get("dept"));
        assertNull(ack.get(0).get("fg"));
    }
}
