# DCS-9 — Phân quyền & vai trò (mô hình 4 vai)

> Kiểm quyền đặt ở tầng **domain**, không tin route (`permissions.ts:1-7`). Route ẩn menu (`nav.ts`) là lớp tiện ích; domain + `requireDocFlow` là lớp thực thi.

## 1. Bốn vai hiện hành

| Mã (`user_roles.role_code`) | Nhãn (`labels.ts`) | Cấp gán (`roles.ts:normalizeScope`) | Vai trò |
|------|------|------|---------|
| `DC_ADMIN` | Quản trị hệ thống | group | Quản trị tổ chức/người dùng/vai trò/nhóm, xem ghi vết. **Đứng NGOÀI luồng tài liệu.** |
| `DC_AUTHOR` | Biên soạn tài liệu | group | Đăng ký/ban hành, "Tài liệu của tôi", sửa/thu hồi/xóa, phát hành thêm site, gói ban hành, kết luận rà soát (nếu thuộc bộ phận sở hữu) |
| `DC_SITE_DOC_CONTROL` | Kiểm soát tài liệu (site) | **site** (bắt buộc chọn site) | Chấp nhận/từ chối receipt của site mình; thẩm định report; theo dõi rà soát ở site mình |
| `DC_READER` | Người đọc | group | Xem tài liệu hiện hành trong phạm vi; xác nhận đọc; in; báo bất thường |

Vai cũ (gác cổng nguồn) `DC_DEPT_HEAD`, `DC_SITE_QA_MANAGER`, `DC_BU_OWNER`, `DC_GROUP_OWNER`: **không gán mới** (`roles.ts:62-66` chặn tường minh), bản ghi cũ bị `is_active=false` (`021`); giữ nhãn để đọc dữ liệu lịch sử (`labels.ts:66-72`).

## 2. Hàm kiểm quyền chủ chốt (`domain/permissions.ts`)

| Hàm | Ý nghĩa | Dòng |
|-----|---------|------|
| `isReadOnlyUser(roles)` | Reader = không Author, không DC Site, không Admin | `:16-21` |
| `allowedStatusesForViewer(roles)` | Reader → `CURRENT_VIEW_STATUSES`; vai nghiệp vụ → null | `:27-29` |
| `canRegisterDocuments(sql,user)` | có `DC_AUTHOR` active | `:31-38` |
| `hasSystemAdminAccess(sql,user)` | có `DC_ADMIN` active | `:40-47` |
| `roleCodesOf(sql,user)` | tập mã vai active (cho menu) | `:50-55` |
| `isSiteDocControl(roles)` | có `DC_SITE_DOC_CONTROL` | `:62-64` |
| `canExportCatalog(roles)` | Author HOẶC DC Site | `:67-69` |
| `isDocFlowRoles/isDocFlowUser` | Author/DC Site/Reader (Admin-thuần = false) | `:72-80` |
| `siteIdsWithDocControl(sql,user)` | tập `site_id` người dùng giữ vai DC Site | `:83-90` |
| `canRespondReceiptPure/canRespondReceipt` | DC Site đúng site của receipt; Admin KHÔNG | `:93-100` |
| `canReportIssue(roles)` | mọi vai trừ Admin-thuần | `:103-106` |

## 3. Ma trận quyền theo thao tác (từ code domain + route)

| Thao tác | Admin | Author | DC Site | Reader | Guard code |
|----------|:-----:|:------:|:-------:|:------:|-----------|
| Xem danh mục / chi tiết (`/documents`) | — **(Admin-thuần: 403)** | ✓ | ✓ | ✓ (chỉ hiện hành + đã tới ngày hiệu lực) | `requireDocFlow`; `listVisibleDocuments`/`canViewVersion` |
| Đăng ký / ban hành (`/register`) | — | ✓ | — | — | `canRegisterDocuments` |
| Tài liệu của tôi + sửa/thu hồi/xóa/phát hành thêm | — | ✓ (người đăng ký) | — | — | `loadOwnedVersion` (registrant) |
| Chấp nhận/từ chối receipt (`/site-receipts`) | — | — | ✓ (đúng site) | — | `assertCanRespond` + `siteIdsWithDocControl` |
| Tự-phản-hồi tài liệu mình | — | (nếu cũng là DC Site) — cho phép nhưng **cảnh báo + audit đậm** | ✓ | — | `tu_phan_hoi_canh_bao` (`receipt.ts`) |
| Báo bất thường (`/documents/:id/report`) | — (Admin-thuần) | ✓ | ✓ | ✓ | `canReportIssue` |
| Thẩm định report (`/issue-reports`) | — | — | ✓ (đúng site) | — | `assertSiteControl` |
| Kết luận rà soát (`/reviews/:id/verdict`) | — | ✓ (thuộc bộ phận sở hữu) | — (chỉ theo dõi) | — | `recordVerdict` quyền `owner_department_id` |
| In bản sao / thu hồi bản in | — | ✓ | ✓ | ✓ (có quyền xem) | `canViewVersion` + `isCurrentlyEffective` |
| Gói ban hành (`/packages`) | — | ✓ | — | — | `canRegisterDocuments` + người tạo/đăng ký |
| So sánh phiên bản (`/compare`) | ✓ | ✓ | ✓ | ✓ (có quyền xem cả 2) | `canViewVersion` |
| Tìm kiếm (`/search`) | ✓ | ✓ | ✓ | ✓ | lọc phạm vi trong `fn_search` |
| Nhóm chức năng / Quản trị org/users/roles / Ghi vết | ✓ | — | — | — | `hasSystemAdminAccess` / `nav.isAdmin` |

Chú thích: Admin-thuần (chỉ `DC_ADMIN`, không vai nghiệp vụ) bị `requireDocFlow` chặn truy cập trang tài liệu (403) — phòng thủ sâu ngoài việc ẩn menu (`middleware/auth.ts`).

> **IND-06 (D7) — Admin đứng NGOÀI luồng tài liệu (sửa tài liệu cho khớp runtime, KHÔNG đổi code):** `/documents` + `/documents/:id` gọi `requireDocFlow` → **Admin-thuần bị 403** trước khi tới `listVisibleDocuments`/`canViewVersion`. Nhánh `role_code='DC_ADMIN'` trong hai hàm đó CHỈ tới được với user có THÊM vai nghiệp vụ (dual-role), KHÔNG áp cho Admin-thuần. Đây là **lệch tài liệu** (bảng cũ ghi Admin "✓ tra cứu"), không phải bug code; bản sửa nằm ở specs này (đã chỉnh ô Admin ở dòng "Xem danh mục/chi tiết" thành "—/403"). Code giữ nguyên + chỉ thêm comment làm rõ nhánh Admin dành cho dual-role (`catalog.ts`). [mới kiểm chứng đọc code — `auth.ts:requireDocFlow`, `permissions.ts:isDocFlowRoles`, `catalog.ts`]

## 4. Phạm vi xem (Reader & scope)

`listVisibleDocuments`/`canViewVersion` (`catalog.ts`): người dùng thấy tài liệu khi (a) mình là người đăng ký, hoặc (b) scope `group`, hoặc (c) thuộc cây tổ chức khớp scope (`bu` qua `org_site_bu`, `site`, `dept`), hoặc (d) có vai `DC_ADMIN` **(nhánh này chỉ hiệu lực với user DUAL-ROLE — Admin-thuần đã bị `requireDocFlow` chặn 403 trước, IND-06/D7)**. Reader thêm điều kiện status hiện hành (`da_ban_hanh/hieu_luc` — IND-05 bỏ `da_dang_ky`) + `effective_date <= CURRENT_DATE` (điểm 4). Lọc TRƯỚC trong truy vấn, không lọc sau.

## 5. Gán vai trò (`domain/roles.ts`)
- `grantRole` idempotent (ON CONFLICT → `is_active=TRUE`), `normalizeScope` ép đúng cấp:
  - Admin/Author/Reader → `group` (không con trỏ) — sai cấp ném `role.scope`.
  - DC_SITE_DOC_CONTROL → `site` + bắt buộc `scope_site_id`.
  - Vai cũ → chặn `role.scope`.
- `revokeRole` đặt `is_active=false` (giữ bản ghi cho audit).
- Ràng buộc DB: `chk_user_role_code`, `chk_user_role_scope` (018 cho DC_SITE_DOC_CONTROL), `chk_role_scope_ref`, `uq_user_role`.
