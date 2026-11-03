package com.dqh.dcs.support;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.dqh.dcs.acknowledgement.AcknowledgementService;
import com.dqh.dcs.audit.AuditService;
import com.dqh.dcs.issuereport.IssueReportService;
import com.dqh.dcs.permissions.PermissionService;
import com.dqh.dcs.receipt.ReceiptService;
import com.dqh.dcs.registration.RegistrationService;

/**
 * support/ItServices — dựng các service domain THẬT cho test tích hợp dùng
 * NamedParameterJdbcTemplate của DbSupport. Các phương thức @Transactional của
 * service là no-op khi gọi trực tiếp (không proxy), nên test phải BỌC lời gọi
 * ghi trong DbSupport.tx().execute(...) để mở giao dịch — writeAudit dùng CHUNG
 * jdbc/DataSource sẽ tham gia cùng giao dịch (đúng hợp đồng audit cùng tx).
 */
public final class ItServices {

    public final NamedParameterJdbcTemplate jdbc;
    public final AuditService audit;
    public final PermissionService permissions;
    public final ReceiptService receipts;
    public final RegistrationService registration;
    public final AcknowledgementService acknowledgement;
    public final IssueReportService issueReports;

    public ItServices() {
        this.jdbc = DbSupport.jdbc();
        this.audit = new AuditService(jdbc, DbSupport.objectMapper());
        this.permissions = new PermissionService(jdbc);
        this.receipts = new ReceiptService(jdbc, audit, permissions);
        this.registration = new RegistrationService(jdbc, audit, permissions, receipts);
        this.acknowledgement = new AcknowledgementService(jdbc, audit);
        this.issueReports = new IssueReportService(jdbc, audit, permissions);
    }

    /** VersionInput hợp lệ mặc định (ngày hiệu lực = hôm nay), tiện tạo fixture. */
    public static com.dqh.dcs.registration.VersionInput versionInput(
            String docId, String authorId, String effectiveDate) {
        String today = com.dqh.dcs.common.Clock.isoTodayVN();
        return new com.dqh.dcs.registration.VersionInput(
                docId, "1.0", "LC " + docId,
                today, null, effectiveDate == null ? today : effectiveDate,
                12, null, "thu_cong",
                "/tmp/lc.pdf", "hash-" + docId, authorId, "",
                null, null, null);
    }

    public static com.dqh.dcs.registration.DocumentInput docInput(String docId) {
        return new com.dqh.dcs.registration.DocumentInput(
                docId, "SOP", "noi_bo", com.dqh.dcs.roles.ScopeLevel.group, null, null, null);
    }
}
