# DCS-10 — Ghi vết (audit) & thuật ngữ

## 1. Nguyên tắc ghi vết

- `audit_log` **append-only**: rule `audit_log_no_update`/`no_delete` chặn sửa/xóa ở tầng DB kể cả Super Admin (`001_schema.sql`).
- **Mọi hàm domain làm thay đổi trạng thái phải ghi vết TRONG CÙNG GIAO DỊCH** — `writeAudit` bắt buộc nhận `tx` (không có biến thể ngoài giao dịch). Nếu thay đổi thành công mà audit hỏng → rollback cả hai (`lib/audit.ts:9-18, :70-90`).
- `actor_type ∈ {nguoi, he_thong, api}`; tác nhân hệ thống (job) để `actor_user_id = NULL`.
- `object_id` cắt 64 ký tự; `before_state/after_state/context` JSONB.

## 2. Danh mục `event_type` (khớp CHECK, đầy đủ sau 027)

**Luồng đăng ký / vòng đời (mô hình cũ + chung):**
`nop_dang_ky`, `rut_lai`, `sua_truoc_khi_nop`, `xac_nhan`, `tu_choi_boi_admin`, `tu_choi_tu_dong`, `tich_checklist`, `chuyen_hieu_luc`, `chuyen_het_hieu_luc`, `thu_hoi`.

**Nhãn liên kết / phân phối:** `khai_nhan`, `sua_nhan`, `xac_nhan_nhan_chep`, `sua_ma_tran`, `gui_thong_bao`.

**Đọc / in / tra cứu / AI:** `xem`, `tai`, `in`, `tra_cuu_qr`, `xac_nhan_doc`, `hoi_ai`, `xem_so_sanh`, `tim_kiem`.

**Rà soát:** `sinh_ra_soat`, `ket_luan_ra_soat`, `dong_ra_soat_theo_ban_moi`.

**Gói ban hành:** `tao_goi`, `gan_vao_goi`, `doi_ngay_goi`, `thao_thanh_vien`, `giai_the_goi`, `kich_hoat_goi`.

**Hệ thống:** `leo_thang`, `sua_khan_cap`, `dong_hau_kiem_khan_cap`, `kiem_toan_ven_tep`, `tieu_huy_ho_so`, `doi_so_hieu`, `dong_bo_l1`.

**Mô hình receipt mới (`017`/`019`/`022`/`027`):**
| Event | Khi nào | Nguồn |
|-------|---------|-------|
| `ban_hanh` | Author đăng ký / phát hành thêm tới site | `receipt.ts:publishToSites:106-111`, `publishAdditionalSites` |
| `chap_nhan_nhan` | DC Site chấp nhận receipt | `receipt.ts:acceptReceipt:424-428` |
| `tu_choi_nhan` | DC Site từ chối receipt (kèm loại) | `receipt.ts:rejectReceipt:463-467`, `issue-report.ts:185-190` |
| `tu_phan_hoi_canh_bao` | Người đăng ký tự phản hồi tài liệu mình (audit đậm) | `receipt.ts:416-421, :454-459` |
| `sua_dang_ky` | Author sửa metadata tại chỗ | `receipt.ts:editVersionMetadata:515-519` |
| `xoa_ban_hanh` | Author xóa bản ban hành chưa ai chấp nhận | `receipt.ts:deleteUnacceptedVersion:553-557` |
| `bao_bat_thuong` | Reader gửi report | `issue-report.ts:createIssueReport:60-64` |
| `chuyen_report_phan_hoi` | DC Site chuyển report → từ chối receipt | `issue-report.ts:179-183` |
| `bac_bo_report` | DC Site bác bỏ report | `issue-report.ts:dismissReport:202-206` |
| `thu_hoi_ban_in` | Thu hồi bản sao in tại hiện trường | `printing.ts:188-192` |
| `bao_sai_noi_dung_sau_chap_nhan` | DC Site báo sai nội dung sau khi site đã chấp nhận (điểm 7) | `issue-report.ts:160-166` |

CHECK danh sách này ở `027_report_content_error_after_accept.sql:24-39` (mở rộng từ 022/019).

## 3. Màn ghi vết (`/audit`, `audit.eta`)
- Chỉ Admin (`nav.isAdmin`); lọc theo `object_id`, `event_type`, khoảng ngày (`from`/`to`). Nguồn `routes/audit.ts`, `domain/audit-query.ts` (`EVENT_TYPES`, `queryAudit`).
- Link từ detail: "Xem ghi vết đầy đủ của phiên bản này" → `/audit?object_id=<version_id>`.

## 4. Thuật ngữ (enum mã → nghĩa, nguồn `labels.ts`)

### Trạng thái phiên bản (`DOC_STATUS_LABELS`)
| Mã | Nhãn |
|----|------|
| `cho_hoan_thien` | Nháp chờ hoàn thiện |
| `da_ban_hanh` | Đã ban hành |
| `het_hieu_luc` | Hết hiệu lực |
| `thu_hoi` | Đã thu hồi |
| `cho_xac_nhan`* | Chờ xác nhận (legacy) |
| `tu_choi`* | Bị trả lại (legacy) |
| `da_dang_ky`* | Đã xác nhận, chờ hiệu lực (legacy) |
| `hieu_luc`* | Đang hiệu lực (legacy) |

### Trạng thái receipt (`RECEIPT_STATUS_LABELS`)
`cho_phan_hoi` = Chờ phản hồi · `chap_nhan` = Đã chấp nhận · `tu_choi` = Đã từ chối.

### Loại từ chối (`RECEIPT_REJECT_TYPE_LABELS`)
`sai_dang_ky` = Sai thông tin đăng ký · `sai_noi_dung` = Sai nội dung tài liệu.

### Trạng thái report (`ISSUE_REPORT_STATUS_LABELS`)
`moi` = Mới — chờ xem xét · `chuyen_phan_hoi` = Đã chuyển thành phản hồi · `bac_bo` = Đã bác bỏ · `bao_sai_noi_dung_sau_chap_nhan` = Báo sai nội dung sau khi đã chấp nhận.

### Trạng thái tổng hợp (`AGGREGATE_STATUS_LABELS` + `aggregateDisplay`)
`chua_phat_hanh` · `dang_cho` (Đang chờ phản hồi) · `mot_phan` (Một phần đã áp dụng) · `tranh_chap` → tách "Có site báo sai nội dung" (đỏ) / "Có site báo sai đăng ký" (amber) · `toan_bo` (Toàn bộ đã áp dụng).

### Vai trò (`ROLE_LABELS`)
`DC_ADMIN` = Quản trị hệ thống · `DC_AUTHOR` = Biên soạn tài liệu · `DC_SITE_DOC_CONTROL` = Kiểm soát tài liệu (site) · `DC_READER` = Người đọc · (legacy: `DC_DEPT_HEAD`, `DC_SITE_QA_MANAGER`, `DC_BU_OWNER`, `DC_GROUP_OWNER` = Phê duyệt vai cũ).

### Nguồn (`ORIGIN_LABELS`)
`noi_bo` = Nội bộ · `ngoai_bo` = Ngoại bộ.

### Cấp phạm vi (`SCOPE_LABELS`)
`group` = Toàn Tập đoàn · `bu` = Khối (BU) · `site` = Nhà máy / Chi nhánh · `dept`/`department` = Phòng/Ban.

### Tình trạng tra cứu QR (`verifyToken`)
`con_hieu_luc` · `het_hieu_luc` · `thu_hoi` · `ban_in_thu_hoi` · `khong_ro` · (theo site) `chua_tiep_nhan` · `tu_choi_tiep_nhan` · `chap_nhan`.

## 5. Chú giải nghiệp vụ ngắn
- **Receipt:** phiếu tiếp nhận của MỘT site cho MỘT phiên bản; đơn vị phản hồi là site (DC Site bấm).
- **Hiện hành:** trạng thái người đọc được phép thấy = `da_ban_hanh` (mới) + `hieu_luc`/`da_dang_ky` (tương thích cũ); nguồn duy nhất `CURRENT_VIEW_STATUSES`.
- **"Đăng ký là ban hành luôn":** Author đăng ký → ban hành thẳng, không gác cổng ở nguồn; kiểm tra chuyển sang các site SAU ban hành.
- **Bối cảnh tại thời điểm xác nhận:** `read_acknowledgements`/`document_receipt` lưu bộ phận/nhóm/site lúc xác nhận; báo cáo không tra lại L1.

---
*Hết bộ tài liệu DCS-1 → DCS-10. Nguồn sự thật: nhánh `dcs-lifecycle-sync-specs`, 27 migration (gồm 026/027).*
