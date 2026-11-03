package com.dqh.dcs.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.dqh.dcs.common.BusinessException;
import com.dqh.dcs.roles.ScopeLevel;

/**
 * ScopeNormalizeTest — chuẩn hoá phạm vi tài liệu (thuần).
 * Tái hiện normalizeDocumentScope trong src/domain/registration.ts: giữ đúng
 * một con trỏ theo cấp, '' → NULL, thiếu con trỏ bắt buộc thì ném BusinessException
 * với mã reg.scope_* Y HỆT chuỗi TS.
 */
class ScopeNormalizeTest {

    private static DocumentInput doc(ScopeLevel level, String bu, String site, String dept) {
        return new DocumentInput("SOP-001", "SOP", "noi_bo", level, bu, site, dept);
    }

    @Test
    void groupXoaMoiConTro() {
        DocumentInput d = RegistrationService.normalizeDocumentScope(
                doc(ScopeLevel.group, "b", "s", "d"));
        assertEquals(ScopeLevel.group, d.scopeLevel());
        assertNull(d.scopeBuId());
        assertNull(d.scopeSiteId());
        assertNull(d.scopeDeptId());
    }

    @Test
    void buGiuBuBoConTroKhac() {
        DocumentInput d = RegistrationService.normalizeDocumentScope(
                doc(ScopeLevel.bu, "bu-1", "", ""));
        assertEquals("bu-1", d.scopeBuId());
        assertNull(d.scopeSiteId());
        assertNull(d.scopeDeptId());
    }

    @Test
    void siteChuoiRongLaThieuNemLoi() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RegistrationService.normalizeDocumentScope(doc(ScopeLevel.site, null, "  ", null)));
        assertEquals("reg.scope_site", ex.getCode());
    }

    @Test
    void deptThieuNemLoiMaDung() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RegistrationService.normalizeDocumentScope(doc(ScopeLevel.dept, null, null, null)));
        assertEquals("reg.scope_dept", ex.getCode());
    }

    @Test
    void buThieuNemLoiMaDung() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RegistrationService.normalizeDocumentScope(doc(ScopeLevel.bu, "", null, null)));
        assertEquals("reg.scope_bu", ex.getCode());
    }
}
