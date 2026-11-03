# DCS-1 — Kiến trúc & Phạm vi

> **Nguồn sự thật:** source code đã merge trong worktree `dcs/.worktrees/dcs-persite-effective-and-fixes`, nhánh `dcs-persite-effective-and-fixes` (bao gồm per-site effective + timezone VN + bug Nhóm A trên nền `dcs-lifecycle-sync-specs`). Mọi tham chiếu `file:dòng`/`file:hàm` trong bộ tài liệu này trỏ vào cây mã đó. Nơi nào comment trong code mâu thuẫn với hành vi runtime, tài liệu theo **hành vi runtime** và ghi chú lại.
>
> **Stack hiện tại (mã nguồn tham chiếu):** TypeScript + Fastify + Eta (SSR) + htmx, PostgreSQL 16 (driver `postgres`). Bộ tài liệu này mô tả **hành vi nghiệp vụ** để tái hiện trên stack Java + Spring Boot + JDBC/MySQL (QLone), nên các điểm phụ thuộc Postgres được đánh dấu rõ ở DCS-6/Điểm mở.

## 1. Phạm vi hệ thống

DCS (Document Control System — Hệ Quản lý & Phân phối Tài liệu Điện tử) phụ trách **khâu SAU phê duyệt** của vòng đời tài liệu:

```
đăng ký → ban hành → phân phối (tới site) → xác nhận đọc → in bản sao kiểm soát → tra cứu / hỏi đáp AI có trích dẫn
```

Soạn thảo và ký duyệt **nằm ngoài phạm vi** (thuộc hệ ký duyệt / QDMS). Tài liệu vào DCS khi đã được phê duyệt offline; người Biên soạn (Author) **đăng ký là ban hành luôn** — xem DCS-2.

Ranh giới ghi rõ trong `src/db/migrations/001_schema.sql:6` ("Phạm vi: khâu SAU phê duyệt. Soạn thảo và ký duyệt thuộc hệ khác") và `src/db/migrations/017_receipt_model.sql:4-11`.

### 1.1 Mô hình vòng đời: từ "duyệt 2 lớp" sang "đăng ký là ban hành"

Hệ thống đã chuyển từ mô hình cũ (Author nộp → Dept Head duyệt trước khi ban hành → người nhận xác nhận đọc) sang mô hình mới:

- Author đăng ký = **ban hành thẳng** (`da_ban_hanh`), không còn lớp gác cổng ở nguồn.
- Các **site nhận** kiểm tra **SAU** ban hành qua **receipt** (chấp nhận / từ chối).

Nguồn: `src/db/migrations/017_receipt_model.sql:4-18`, `src/domain/lifecycle-status.ts:1-20`.

## 2. Sơ đồ module

```
┌───────────────────────── SSR (Eta views + htmx) ─────────────────────────┐
│ layout.eta · catalog.eta · detail.eta · register.eta · issuance-status.eta │
│ site-receipts.eta · receipt-respond.eta · reviews.eta · issue-reports.eta  │
│ packages.eta · compare.eta · verify.eta · admin/* · components/*           │
└───────────────────────────────────────────────────────────────────────────┘
                 │ render.ts (trộn nhãn labels.ts vào dữ liệu)
┌──────────────────────────────── Routes (Fastify) ─────────────────────────┐
│ home · auth · catalog · documents · receipts · issue-reports · reviews     │
│ printing · packages · diff · search · functional-groups · admin · audit    │
│ mcp (MCP server chỉ-đọc)                                                    │
└───────────────────────────────────────────────────────────────────────────┘
                 │  middleware/auth.ts (phiên) · middleware/nav.ts (menu + badge)
┌──────────────────────────────── Domain (nghiệp vụ) ───────────────────────┐
│ registration · receipt · issue-report · review · acknowledgement · catalog │
│ lifecycle · lifecycle-status · permissions · printing · package · search   │
│ distribution · diff · content/extract/ocr/chunking · org/users/roles       │
│ auth · session · escalation · functional-groups · audit-query · labeling   │
└───────────────────────────────────────────────────────────────────────────┘
                 │  db/client.ts (pool `postgres`) · lib/audit.ts · lib/errors.ts
┌──────────────────────────────── PostgreSQL 16 ────────────────────────────┐
│ 28 migration (001→028) · hàm/trigger PL/pgSQL cho vòng đời theo lịch        │
└───────────────────────────────────────────────────────────────────────────┘
                 │                               │
     ┌───────────┴─────────┐        ┌────────────┴───────────┐
     │ jobs (oneshot)       │        │ Tích hợp ngoài          │
     │ scheduler · ocr-worker│        │ QDMS (nhãn/điều khoản)  │
     │ qua systemd timer     │        │ L1 (định danh/cây tổ chức)│
     └──────────────────────┘        └────────────────────────┘
```

Chi tiết:
- **Tầng route** mỏng, chỉ điều phối và bắt `BusinessError`; mọi kiểm quyền + quy tắc đặt ở **domain** (`src/middleware/auth.ts:76-94`, `src/domain/permissions.ts:1-7`).
- **Đường duy nhất lấy người dùng hiện tại:** `getCurrentUser` (`src/middleware/auth.ts:62-64`). Không nơi nào đọc cookie phiên trực tiếp (chuẩn bị cho OIDC/L1).
- **Cờ menu theo vai trò** dựng ở `src/middleware/nav.ts:36-69`; ẩn hẳn mục không có quyền.
- **Tác vụ nền** chạy như tiến trình **oneshot** `src/jobs/scheduler.ts:19-28`, gọi qua systemd timer, KHÔNG chạy trong tiến trình web (xem DCS-6).

## 3. Luồng dữ liệu chính (đăng ký → ban hành → phản hồi)

```
Author điền /register (register.eta)
   │ POST /register  (routes/documents.ts)
   ▼
submitRegistration (domain/registration.ts:154)
   │  canRegisterDocuments? → ensureDocument → validateForSubmit
   │  (nếu supersede) bản gốc → 'het_hieu_luc'    [giữ liên kết phiên bản]
   │  INSERT document_versions status='da_ban_hanh'
   │  (thay đổi 'nho') sao chép read_acknowledgements từ bản gốc
   │  publishToSites → sinh document_receipt 'cho_phan_hoi' cho mỗi site nhận
   ▼
processVersionContent (best-effort: bóc tách + chỉ mục văn bản)
   ▼
DC Site mở /site-receipts → /site-receipts/:id
   │  acceptReceipt   → receipt 'chap_nhan' + apply_date = coalesce(site_effective_date, effective_date)  (chặn nếu chưa tới ngày hiệu lực site / đã thu hồi / hết hiệu lực)
   │  rejectReceipt   → receipt 'tu_choi' + response_type (sai_dang_ky | sai_noi_dung) + lý do
   ▼
Reader /documents/:id → Báo bất thường (POST /documents/:id/report)
   │  DC Site thẩm định /issue-reports → convert (→ từ chối receipt) | dismiss
   ▼
Tác vụ nền (scheduler) hằng ngày:
   fn_activate_due_versions · fn_generate_due_reviews · fn_run_escalations · purgeExpiredSessions
```

Nguồn: `src/routes/documents.ts:160-250`, `src/domain/registration.ts:154-262`, `src/domain/receipt.ts:83-115`, `src/routes/receipts.ts`, `src/domain/issue-report.ts`, `src/jobs/scheduler.ts:19-28`.

## 4. Danh sách bảng (sinh từ migration thật)

> **Tổng số migration thực trên nhánh `dcs-persite-effective-and-fixes`: 28** (`001_schema.sql` … `028_site_effective_date.sql`), **bao gồm 026 (`lifecycle_sync`), 027 (`report_content_error_after_accept`), 028 (`site_effective_date` — ngày hiệu lực per-site)**. (Đếm thực tế thư mục `src/db/migrations`, không phải con số cũ 16/25/27 trong tài liệu lạc hậu — xem DCS-7/DCS-8.)

### 4.1 Bảng tổ chức & định danh (bản đọc từ L1, giai đoạn 1 DCS tự giữ)

| Bảng | Vai trò | Nguồn |
|------|---------|-------|
| `org_bu` | Ngành hàng (L2) | `001_schema.sql:62` |
| `org_site` | Nhà máy / pháp nhân (L3); cột `bu_id` đổi thành `legacy_primary_bu_id` ở 005 | `001_schema.sql:69`, `005:60-63` |
| `org_site_bu` | Site ↔ BU N:N (thay cột 1-1 cũ) | `005_ver2_organization_authorization.sql:66` |
| `org_department` | Phòng ban (L4), có `manager_user_id` | `001_schema.sql:76` |
| `users` (đổi tên từ `app_user_ref`) | Người dùng DCS; thêm `password_hash` (003), `primary_dept_id` (015) | `003:16`, `005:20`, `015:12` |
| `user_departments` (đổi tên từ `app_user_dept`) | Gán người ↔ bộ phận (N:N), `is_active` | `001_schema.sql:95`, `005:20` |
| `user_roles` (đổi tên từ `app_user_role`) | Vai trò theo phạm vi | `003:38`, `005:20`, `018` |
| `app_session` | Phiên làm việc lưu DB, TTL 8 giờ | `003_auth_giai_doan_1.sql:26` |
| `functional_groups`, `functional_group_members` | Nhóm chức năng đọc tài liệu | `001_schema.sql:130`, `:151` |

### 4.2 Bảng tài liệu & vòng đời

| Bảng | Vai trò | Nguồn |
|------|---------|-------|
| `documents` | Tài liệu theo số hiệu (`doc_id` là PK người nhập); mang `doc_type`, `origin`, `scope_*` | `001_schema.sql:175` |
| `document_versions` | Phiên bản; mang trạng thái vòng đời, ngày hiệu lực/hết hạn, supersede, change_significance | `001_schema.sql:224` |
| `release_packages` | Gói ban hành phối hợp (2–10 tài liệu) | `001_schema.sql:191` |
| `document_sections`, `document_chunks` | Mục + phân đoạn đã bóc tách (phục vụ tìm kiếm / AI) | `001_schema.sql:~330`, `:~360` |
| `document_links` | Nhãn liên kết tới QDMS (điều khoản, công đoạn…) | `001_schema.sql:~395` |
| `document_site_rollout` | Áp dụng lệch pha theo site (mô hình cũ, còn để lọc danh mục) | `011_site_rollout.sql:3` |
| `document_receipt` | **MỚI** — mỗi dòng = (phiên bản × site): phản hồi + apply_date + **`site_effective_date` (ngày hiệu lực riêng/site — `028`)** | `017_receipt_model.sql:45`, `028_site_effective_date.sql` |
| `document_issue_report` | **MỚI** — report bất thường của Reader, DC Site thẩm định | `017_receipt_model.sql:111` |
| `read_acknowledgements` | Xác nhận đọc theo phiên bản, lưu bối cảnh tại thời điểm xác nhận | `001_schema.sql:~430` |
| `distribution_matrix` | Ma trận "ai phải đọc" theo bộ phận × nhóm | `001_schema.sql:~415` |
| `printed_copies` | Bản in kiểm soát + `verify_token` ngẫu nhiên cho QR | `001_schema.sql:~445` |
| `periodic_reviews` | Rà soát định kỳ (bản ghi có vòng đời riêng) | `001_schema.sql:~480` |
| `m18_findings`, `m18_finding_targets` | Phát hiện từ QDMS M18 (DCS chỉ hiển thị) | `001_schema.sql:~520` |
| `escalation_rules`, `escalations_sent` | Leo thang tồn đọng (chỉ thông báo) | `001_schema.sql:~555`, `:~600` |
| `audit_log` | Ghi vết append-only (chặn UPDATE/DELETE ở DB) | `001_schema.sql:~620` |

### 4.3 View & hàm nghiệp vụ (Postgres)

- View: `v_current_documents` (hiện hành; mở rộng `da_ban_hanh` ở `020`), `v_clause_references`, `v_search_index`, `v_review_backlog`, `v_backlog_all`, `app_user_*` (tương thích đọc).
- Hàm: `fn_activate_due_versions` (chuyển hiệu lực theo lịch — `026` gỡ khối tự hết hạn), `fn_generate_due_reviews` (`026` quét cả `da_ban_hanh`), `fn_record_review_verdict`, `fn_run_escalations`, `fn_search`/`fn_search_limited` (`023`/`024`/`025` thêm lọc đa chiều), `fn_diff_versions`, `fn_diff_reliability`, `fn_has_viewed_diff`.

## 5. ERD (quan hệ chính)

```
org_bu 1───* org_site *───* org_bu            (org_site_bu)
org_site 1───* org_department 1───* user_departments *───1 users
users 1───* user_roles (role_code, scope_level, scope_*)
users 1───* app_session

documents(doc_id PK) 1───* document_versions(version_id PK)
   document_versions.supersedes_version_id ──► document_versions (tự tham chiếu — liên kết phiên bản)
   document_versions.package_id ──► release_packages
document_versions 1───* document_sections 1───* document_chunks
document_versions 1───* document_links ──► QDMS (link_type, link_code)
document_versions 1───* document_receipt *───1 org_site        (uq: version_id, site_id)
document_versions 1───* document_issue_report *───1 org_site
document_versions 1───* read_acknowledgements *───1 users
documents         1───* distribution_matrix *──1 org_department (×functional_groups)
document_versions 1───* printed_copies (verify_token UNIQUE)
document_versions 1───* periodic_reviews
audit_log (append-only; object_type/object_id TEXT trỏ tự do)
```

Ràng buộc nghiệp vụ chủ chốt (xem DCS-6 để đầy đủ):
- `uq_one_effective_per_doc` — mỗi `doc_id` tối đa 1 bản `hieu_luc` (mô hình cũ), `001_schema.sql`.
- `uq_one_current_per_doc` — mỗi `doc_id` tối đa 1 bản `da_ban_hanh` (mô hình mới), `026_lifecycle_sync.sql:~95`.
- `uq_receipt_version_site` — mỗi (phiên bản × site) một receipt, `017_receipt_model.sql:~78`.

## 6. Nguyên tắc trình bày

Mọi mục ở DCS-2→DCS-6 kèm tham chiếu `file:dòng`, suy từ code xử lý thật, không suy diễn. Các điểm chưa rõ / chưa implement gom ở **DCS-7 §"Điểm mở"**.
