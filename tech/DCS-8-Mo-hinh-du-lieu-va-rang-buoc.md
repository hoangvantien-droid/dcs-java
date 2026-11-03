# DCS-8 — Mô hình dữ liệu & ràng buộc (sinh từ 28 migration thật)

> Bổ trợ cho ERD ở DCS-1. Liệt kê bảng/cột/ràng buộc nghiệp vụ quan trọng, suy từ `src/db/migrations/001`…`028`. **Tổng 28 migration** (gồm 026/027/028).
>
> **F3 — số migration (đã kiểm chứng đọc code):** baseline bộ tài liệu cũ ghi "27 migration (001…027)". Đợt sửa per-site thêm **`028_site_effective_date.sql`** → tổng thực tế **28**. Mọi chỗ còn ghi 16/25/27 là lạc hậu.
>
> **F5 — nhãn kiểm chứng:** các khẳng định về code trong bộ tài liệu này gắn một trong ba mức: **[đã kiểm chứng test DB]** (có test tích hợp chạm Postgres xác nhận — chạy trên VPS); **[mới kiểm chứng đọc code]** (đọc code đã merge trong worktree, chưa chạy runtime — do máy không có Postgres local nên integration test tự skip); **[chưa kiểm chứng runtime]** (suy từ code nhưng chưa có test/chạy xác nhận hành vi đầu-cuối). Mặc định các mục migration/cột mới ở đây là **[mới kiểm chứng đọc code]**.

## 1. Lịch sử migration (append-only, chạy theo thứ tự số, mỗi file một giao dịch)

| # | File | Nội dung chính |
|---|------|----------------|
| 001 | `001_schema.sql` | Schema chính: org, functional_groups, documents, document_versions, sections/chunks/links, release_packages, distribution_matrix, read_acknowledgements, printed_copies, periodic_reviews, m18, escalation, audit_log; hàm/trigger/view |
| 002 | `002_phu_luc_giai_doan_1.sql` | Phụ lục giai đoạn 1 |
| 003 | `003_auth_giai_doan_1.sql` | `password_hash`, `app_session`, `app_user_role` (vai theo phạm vi) |
| 004 | `004_fix_escalation_targets.sql` | Sửa đích leo thang |
| 005 | `005_ver2_organization_authorization.sql` | Đổi tên `app_user_*`→`users/user_departments/user_roles`; `org_site_bu` N:N; 7 vai Ver2; định nghĩa lại `fn_search`/`fn_run_escalations` |
| 006 | `006_fix_verify_token_length.sql` | Sửa độ dài verify_token |
| 007 | `007_draft_mien_kiem_truoc_khi_nop.sql` | Nháp miễn kiểm trước khi nộp |
| 008 | `008_verify_token_urlsafe.sql` | verify_token url-safe |
| 009 | `009_dept_code_unique_per_site.sql` | dept_code unique theo site |
| 010 | `010_drop_approval_fields.sql` | Bỏ 4 cột phê duyệt (`approver_name`, `approval_reference`, `approval_url`, `approval_method`) |
| 011 | `011_site_rollout.sql` | `document_site_rollout` (áp dụng lệch pha theo site) |
| 012 | `012_roles_3vai.sql` | 3 vai gán cấp hệ thống |
| 013 | `013_roles_3vai_map.sql` | Ánh xạ vai |
| 014 | `014_drop_legacy_columns.sql` | Dọn cột cũ |
| 015 | `015_add_primary_dept.sql` | `users.primary_dept_id` + tạo lại view `app_user_ref` |
| 016 | `016_fulltext_markdown.sql` | Full-text markdown |
| 017 | `017_receipt_model.sql` | **Mô hình receipt**: `da_ban_hanh`, `document_receipt`, `document_issue_report` |
| 018 | `018_role_site_doc_control.sql` | Vai `DC_SITE_DOC_CONTROL` (cấp site) |
| 019 | `019_audit_events_receipt.sql` | Sự kiện audit cho receipt |
| 020 | `020_search_current_da_ban_hanh.sql` | `fn_search` + `v_current_documents` coi `da_ban_hanh` là hiện hành |
| 021 | `021_deactivate_legacy_release_roles.sql` | Vô hiệu vai gác cổng cũ (`is_active=false`) |
| 022 | `022_audit_event_thu_hoi_ban_in.sql` | Sự kiện `thu_hoi_ban_in` |
| 023 | `023_search_filters.sql` | `fn_search` lọc đa chiều + làm giàu kết quả |
| 024 | `024_search_fix_origin.sql` | Sửa cột origin trong tìm kiếm |
| 025 | `025_search_scope_ids.sql` | Lọc theo BU/Site/Dept cụ thể |
| **026** | `026_lifecycle_sync.sql` | **Đồng bộ vòng đời mới**: `fn_generate_due_reviews` quét `da_ban_hanh` (điểm 2); gỡ khối tự hết hạn trong `fn_activate_due_versions` (điểm 9); `uq_one_current_per_doc` (điểm 1) |
| **027** | `027_report_content_error_after_accept.sql` | Đường "báo sai nội dung sau khi đã chấp nhận" (điểm 7): status `bao_sai_noi_dung_sau_chap_nhan`, event mới |
| **028** | `028_site_effective_date.sql` | **Ngày hiệu lực per-site (phương án 2a)**: thêm cột `document_receipt.site_effective_date DATE` (NULL = coalesce về `effective_date` gốc); hàm `fn_check_site_effective()` + trigger `trg_check_site_effective` chặn `site_effective_date < issued_date`. KHÔNG đặt CHECK "không về quá khứ" ở DB (ràng buộc theo mốc runtime thuộc tầng domain — D4) |

## 2. `documents`
- PK `doc_id VARCHAR(50)` (người nhập, không tự sinh); `ON UPDATE CASCADE` cho khóa ngoài (đổi số hiệu tự lan truyền).
- `doc_type ∈ {SOP,WI,Form,Policy,External}`; `origin ∈ {noi_bo,ngoai_bo}`; `scope_level ∈ {group,bu,site,dept}` với con trỏ `scope_bu_id/site_id/dept_id`.
- `chk_scope_ref`: đúng 1 con trỏ theo cấp (group không cần con trỏ). Nguồn `001_schema.sql`.

## 3. `document_versions` (bảng trung tâm)
- `status ∈ {cho_hoan_thien, da_ban_hanh, thu_hoi, het_hieu_luc, (legacy) cho_xac_nhan, tu_choi, da_dang_ky, hieu_luc}` (CHECK `017:30-39`).
- Hồ sơ hiệu lực: `issued_date`, `external_effective_date`, `effective_date` XOR `package_id` (`chk_effective_or_package`), `original_effective_date`, `expiry_date`, `review_cycle_months`, `next_review_date`.
- **Mô hình 5 trường ngày (đọc code đã merge — quyết định D1-D3):**
  1. `issued_date` — ngày BAN HÀNH / ngày trên hồ sơ của phiên bản (mốc gốc cho mọi ràng buộc ngày).
  2. `external_effective_date` — ngày hiệu lực PHÁP LÝ ngoại bộ (chỉ khai khi `origin='ngoai_bo'`); thông tin tham chiếu, không điều khiển guard nội bộ.
  3. `effective_date` — **ngày hiệu lực DCS GỐC**: ngày hiệu lực khai lúc đăng ký cho tập site đầu. Là nguồn coalesce khi site không khai ngày riêng.
  4. `original_effective_date` — **[ẩn ở đăng ký]** dành cho NẠP DỮ LIỆU CŨ (`migrated_record=TRUE`): lưu ngày hiệu lực gốc của tài liệu cũ trong khi `effective_date` đặt = hôm nay để thỏa guard. Form `/register` KHÔNG có trường này (chỉ `migration-load.ts` ghi); chưa dùng ở luồng đăng ký thường. [mới kiểm chứng đọc code — `migration-load.ts`, `register.eta` không có field]
  5. `site_effective_date` — **(MỚI, `028`)** ngày hiệu lực RIÊNG mỗi site, nằm ở `document_receipt` (xem §4), KHÔNG ở `document_versions`. `NULL` ⇒ coalesce về `effective_date` gốc.
- Supersede: `supersedes_version_id` (tự tham chiếu); `change_significance ∈ {lon,nho}`, `change_summary`. `chk_change_significance` (supersede → bắt buộc khai), `chk_change_summary` (lớn → bắt buộc tóm tắt).
- Tệp: `source_channel ∈ {api,thu_cong}`, `file_path`, `file_hash` (SHA-256), `file_page_count`.
- Mốc: `submitted_at`, `confirmed_at` (= "ngày đăng ký" cũ), `effective_at`, `updated_at`.
- Nội dung: `parse_status`, `index_status` — **không chặn hiệu lực**.
- `search_tsv` GENERATED từ `doc_id/title/scope/keywords` (Postgres tsvector).
- **Ràng buộc 1 bản hiện hành:**
  - `uq_one_effective_per_doc` (partial WHERE `status='hieu_luc'`, `001`).
  - `uq_one_current_per_doc` (partial WHERE `status='da_ban_hanh'`, `026` — điểm 1).
- `chk_effective_after_confirm`, `chk_expiry_after_effective`, `chk_rejection_reason`, `chk_recall_reason`.
- Trigger: `fn_set_updated_at` (tính lại `next_review_date` khi đổi `effective_date`/`review_cycle_months`), `fn_set_next_review` (INSERT), `fn_sync_chunk_current` (đồng bộ `is_current`), `fn_validate_version` (sau 010: ép ≥1 nhãn liên kết khi rời `da_dang_ky/hieu_luc`), `fn_validate_package_member`/`fn_guard_package_date`.

## 4. `document_receipt` (mô hình mới — `017` + `028`)
- PK `receipt_id`; FK `version_id`, `site_id`, `target_dept_id?`.
- `apply_date` (ghi khi `chap_nhan`), `status ∈ {cho_phan_hoi,chap_nhan,tu_choi}`, `response_type ∈ {sai_dang_ky,sai_noi_dung}`, `response_note`, `responded_by/at`.
- **`site_effective_date DATE` (MỚI — `028`, phương án 2a):** ngày hiệu lực RIÊNG khai cho site này; `NULL` ⇒ dùng ngày hiệu lực gốc `document_versions.effective_date` (coalesce). Mô hình: một thực thể nội dung (doc_id + version_no, một file/hash — `017`) dùng chung mọi site; khác biệt theo site chỉ là mốc hiệu lực + phản hồi, KHÔNG nhân bản tài liệu/phiên bản (quyết định D1).
- **`apply_date` khi chấp nhận = `coalesce(site_effective_date, effective_date)`** — KHÔNG phải ngày bấm chấp nhận (quyết định D2; xem DCS-5 BR-13 và DCS-3 §5). [mới kiểm chứng đọc code — `receipt.ts:acceptReceipt`]
- Ràng buộc: `uq_receipt_version_site` (1 receipt / version × site), `chk_receipt_reject_type`, `chk_receipt_reject_note`, `chk_receipt_responded`, `chk_receipt_apply_date` (chấp nhận phải có apply_date — nền cho điểm 8).
- **Trigger `trg_check_site_effective` (`028`):** `BEFORE INSERT OR UPDATE OF site_effective_date` → `fn_check_site_effective()` tra `issued_date` theo `version_id`, `RAISE EXCEPTION` nếu `site_effective_date < issued_date` (chỉ kiểm khi NOT NULL). Ràng buộc "không về quá khứ (hôm nay VN)" ĐẶT Ở DOMAIN, không ở DB (D4).
- Index: `idx_receipt_version/site/status`, `idx_receipt_pending` (partial WHERE `cho_phan_hoi`).

## 5. `document_issue_report` (mô hình mới — `017` + `027`)
- `status ∈ {moi, chuyen_phan_hoi, bac_bo, bao_sai_noi_dung_sau_chap_nhan}` (cột nới `VARCHAR(40)` ở `027`).
- `site_id` (của người báo), `reported_by`, `note` (not empty), `reviewed_by/at`, `review_note`.
- Ràng buộc: `chk_report_note`, `chk_report_reviewed`, `chk_report_reject_note`.

## 6. `read_acknowledgements`
- PK `ack_id`; `uq_ack (version_id, user_id)` (ON CONFLICT cho kế thừa). Lưu `department_id`/`functional_group_id` TẠI THỜI ĐIỂM xác nhận; `inherited_from_version_id` (phân biệt xác nhận thật vs kế thừa). Gắn với **phiên bản** (đã đọc 1.0 ≠ đã đọc 2.0).

## 7. `release_packages`
- `status ∈ {dang_mo, da_kich_hoat, da_giai_the}`; `effective_date` là nguồn ngày hiệu lực cho thành viên; `chk_dissolve_reason`. Tối đa 10 thành viên (trigger). `idx_package_open` partial.

## 8. `printed_copies`
- `verify_token VARCHAR(43)` UNIQUE, DEFAULT `gen_random_bytes(32)` base64 (ngẫu nhiên, chống dò). `recall_status ∈ {dang_luu_hanh, da_thu_hoi, khong_ro}`. `overlay_applied`.

## 9. `periodic_reviews`
- `status ∈ {dang_ra_soat, treo_cho_ban_moi, da_dong, huy_bo}`; `verdict ∈ {giu_nguyen, can_sua, huy_bo}`; `chk_verdict_by_status`, `chk_verdict_note`. `uq_one_open_review_per_version` (partial). `resolved_by_version_id`.

## 10. Tổ chức & phân quyền
- `org_bu`, `org_site` (cột `bu_id`→`legacy_primary_bu_id` ở 005), `org_site_bu` (N:N), `org_department` (`manager_user_id`, `dept_code` unique per site ở 009).
- `users` (`password_hash` 003, `primary_dept_id` 015, `is_active`, `full_name`, `email`), `user_departments` (N:N, `is_active`), `user_roles` (xem DCS-9), `app_session` (TTL 8h).
- `functional_groups` (`owner_user_id`, `review_cycle_months`, `next_review_date`) + `functional_group_members`.
- View tương thích đọc `app_user_ref/_dept/_role` (không auto-updatable — chặn ghi từ mã cũ).

## 11. `audit_log` (append-only)
- `event_type` CHECK mở rộng qua 019/022/027 (danh sách đầy đủ ở DCS-10). `object_type`/`object_id` TEXT tự do. `before_state/after_state/context` JSONB. Rule `audit_log_no_update`/`no_delete` chặn sửa/xóa kể cả Super Admin.

## 12. View & hàm (Postgres) — xem DCS-6 §8 cho ghi chú port MySQL
- View: `v_current_documents` (020), `v_clause_references`, `v_search_index`, `v_review_backlog`, `v_backlog_all`, `v_distribution_resolved` (phục vụ ack), `v_ack_pending`.
- Hàm: `fn_activate_due_versions` (026), `fn_generate_due_reviews` (026), `fn_record_review_verdict`, `fn_run_escalations` (005), `fn_search`/`fn_search_limited` (025), `fn_diff_versions`/`fn_diff_reliability`/`fn_has_viewed_diff`, `vn_unaccent`/`vn_tsv`/`vn_join`.

## 13. Danh sách loại tài liệu
`DOC_TYPES` ở `domain/doc-types.ts:21-27` = `SOP, WI, Form, Policy, External`, PHẢI khớp CHECK `documents.doc_type` — thêm loại mới cần (1) migration mở CHECK + (2) sửa `DOC_TYPES`.
