# DCS-6 — Tích hợp & vận hành

## 1. QDMS (hệ quản lý chất lượng nguồn)

- **Nhãn liên kết (`document_links`):** DCS trỏ sang mã trong QDMS qua `link_type ∈ {dieu_khoan, cong_doan, diem_kiem_soat, moi_nguy, tai_lieu}` + `link_code`. Nhãn do người thật tạo (`created_by` luôn là người, không có nhãn máy tạo). Nguồn: `001_schema.sql` (bảng `document_links`), hiển thị ở `detail.eta` (panel "Nhãn liên kết"), nạp `catalog.ts:loadLinks`.
- **Tra cứu ngược:** view `v_clause_references` cho QDMS tra tài liệu theo `link_type, link_code` khi điều khoản đổi trạng thái.
- **Phát hiện M18:** `m18_findings` + `m18_finding_targets` — DCS **chỉ hiển thị**, không cho đóng phát hiện (việc đó thuộc M18, tránh hai nơi cùng theo dõi một danh sách tồn đọng). Nguồn: `001_schema.sql`.
- **Quy cách / kế hoạch chất lượng KHÔNG thuộc DCS** — là dữ liệu có cấu trúc của QDMS, không đưa vào `documents.doc_type` (giữ `SOP/WI/Form/Policy/External`). Ghi chú ở `001_schema.sql:176-183`.

## 2. L1 — tầng định danh & cây tổ chức dùng chung

- **Nguyên tắc nền:** L1 là nguồn sự thật cho định danh, cây tổ chức, phân quyền. DCS giữ **bản đọc** (`org_bu/org_site/org_department/users/user_departments`) để tính "ai phải đọc tài liệu này" mà không phải gọi API L1 cho từng dòng báo cáo. Nguồn: `001_schema.sql:50-120`.
- **Giai đoạn 1 (L1 CHƯA vận hành):** DCS tự giữ mật khẩu (`users.password_hash`, argon2id), phiên (`app_session`), vai trò (`user_roles`). Toàn bộ CÓ HẠN SỬ DỤNG — khi L1 lên chuyển sang OIDC và đồng bộ vai trò, gỡ các bảng/cột này. Nguồn: `003_auth_giai_doan_1.sql:3-11`; đường duy nhất lấy user `middleware/auth.ts:62-64` (chỉ sửa một hàm khi đổi OIDC).
- **LƯU BỐI CẢNH TẠI THỜI ĐIỂM XÁC NHẬN — KHÔNG tra lại L1 sau:** `read_acknowledgements` lưu `department_id` + `functional_group_id` khi người dùng bấm xác nhận đọc; báo cáo tồn đọng về sau KHÔNG tra lại L1 (người đổi bộ phận/rời nhóm không làm sai bằng chứng). Tương tự, `document_receipt` ghi `apply_date`/bối cảnh site tại thời điểm chấp nhận. Nguồn: `acknowledgement.ts:46-72`, `001_schema.sql` (comment bảng `read_acknowledgements`), `017_receipt_model.sql:45-58`.
- **Chặn ghi lên bản đọc (giai đoạn 001/002):** rule `*_no_write` chặn UPDATE; từ Ver2 (005) DCS sở hữu cây tổ chức nên các rule này được DROP (`005:40-45`).

## 3. Tác vụ nền (job) — oneshot qua systemd timer

- **KHÔNG chạy trong tiến trình web.** Chạy như tiến trình **oneshot** để trả sạch bộ nhớ sau khi xong (máy 1 GB RAM). Nguồn: `jobs/scheduler.ts:3-10`.
- **Nội dung hằng ngày (`runDailyJobs`, `scheduler.ts:19-28`):**
  1. `activateDueVersions` → `fn_activate_due_versions` (chuyển hiệu lực theo lịch; **không** còn tự hết hạn).
  2. `generateDueReviews` → `fn_generate_due_reviews` (sinh rà soát, quét `da_ban_hanh` + `hieu_luc`).
  3. `runEscalations` → `fn_run_escalations` (chỉ thông báo).
  4. `purgeExpiredSessions` (dọn phiên hết hạn).
  - `ExecStartPost`: `ocr-worker.js` xử lý MỘT tệp OCR/bóc tách mỗi lần (RAM 1 GB).
- **systemd units (`deploy/`):**
  - `dcs-jobs.service`: `Type=oneshot`, `User/Group=dcs`, `ExecStart=/usr/bin/node dist/jobs/scheduler.js`, `ExecStartPost=.../ocr-worker.js`.
  - `dcs-jobs.timer`: `OnCalendar=*-*-* 02:00:00`, `Persistent=true` (chạy 02:00 hằng ngày, bù nếu máy tắt).

## 4. Lưu trữ tệp (file storage)

- Tệp PDF gốc lưu **ngoài CSDL** tại `FILE_STORAGE_PATH`, đường dẫn `năm/tháng/uuid.pdf`; CSDL chỉ giữ `file_path` + `file_hash` (SHA-256). Ghi một lần, bất biến (`flag: 'wx'`). Nguồn: `domain/file-store.ts:35-52`.
- Mục đích: sao lưu nhanh + kiểm toàn vẹn định kỳ (so `file_hash`). Bóc tách nội dung (`content.ts`/`extract.ts`/`ocr.ts`) chạy best-effort, không chặn ban hành.

## 5. Phiên làm việc (session)

- Lưu DB (`app_session`), TTL **8 giờ** (`SESSION_TTL_MS`, `session.ts:12`). Cookie `dcs_sid` chỉ mang `session_id` đã ký (httpOnly, sameSite=lax, signed, maxAge 8h). `resolveSession` cập nhật `last_seen_at`, loại phiên hết hạn hoặc user `is_active=false` (`session.ts:40-51`).
- Dọn phiên hết hạn do tác vụ nền (`purgeExpiredSessions`, `session.ts:60-63`).

## 6. Tìm kiếm & hỏi đáp AI có trích dẫn

- **Tìm kiếm văn bản thuần:** `fn_search`/`fn_search_limited` dùng `search_tsv` + `pg_trgm`, KHÔNG đọc embedding/`index_status` — chạy được cả khi lớp AI ngừng. Lọc phạm vi/hiệu lực/đa chiều là điều kiện truy vấn (lọc TRƯỚC). Nguồn: `domain/search.ts:1-7`, `025_search_scope_ids.sql`.
- **MCP server (`/mcp`):** tác nhân AI truy vấn CHỈ-ĐỌC, luôn lọc quyền theo `user_id` thật của phiên (`resolveIdentity`), kết quả giới hạn (`DEFAULT=20`, `MAX=50`, `snippet≤300`) và kèm trích dẫn (tái dùng `search` + `canViewVersion`). Nguồn: `routes/mcp.ts:1-8,:20-26,:95-110`.
- **Hỏi đáp AI chỉ chạy từ phân đoạn hiện hành:** `document_chunks.is_current` đồng bộ theo trạng thái phiên bản (trigger `fn_sync_chunk_current`), chặn trả lời từ tài liệu hết hiệu lực.

## 7. Cấu hình (.env) & bảo mật vận hành

Biến (`.env.example`, `config.ts`):
| Biến | Bắt buộc | Ghi chú |
|------|----------|---------|
| `DATABASE_URL` | ✓ | thiếu → dừng khởi động |
| `SESSION_SECRET` | ✓ | ≥ 32 ký tự (`config.ts:73-77`) |
| `FILE_STORAGE_PATH` | ✓ | kho tệp PDF |
| `DATABASE_URL_TEST` | (test) | tên phải chứa "test" |
| `PORT` | — | mặc định 3000 |
| `DB_POOL_MAX` | — | mặc định 10 |
| `BASE_PATH` | — | path con sau proxy (vd `/dcs`) |
| `PUBLIC_URL` | — | URL external cho QR |
| `EMBEDDING_API_URL/KEY` | — | thiếu → tắt hỏi đáp AI, phần còn lại vẫn chạy |
| `OCR_ENGINE`, `TESSERACT_PATH` | — | mặc định tesseract / `/usr/bin/tesseract` |

- **`.env` thật KHÔNG đi kèm gói phát hành** (chứa mật khẩu DB + session secret). Chỉ phát `.env.example`. Nếu gói đã gửi ra ngoài → rotate secret (việc thủ công, xem DCS-7 §Điểm mở). Nguồn vận hành: `deploy/deploy.sh`, `README-DEPLOY.md`.
- Ghi vết `audit_log` append-only (chặn UPDATE/DELETE ở DB, `001_schema.sql`); đọc qua `/audit` (chỉ Admin).

## 8. Ghi chú port sang Java + Spring Boot / JDBC-MySQL (QLone)

Các điểm phụ thuộc Postgres cần thay khi port (chi tiết ở DCS-7 §Điểm mở):
- Hàm/trigger PL/pgSQL (`fn_activate_due_versions`, `fn_generate_due_reviews`, `fn_record_review_verdict`, `fn_run_escalations`, `fn_search*`, `fn_diff_*`) → dịch sang service Java trong giao dịch, hoặc stored procedure MySQL.
- `tsvector`/`pg_trgm`/`unaccent`/`vector` → thay bằng FULLTEXT index MySQL + chuẩn hoá bỏ dấu ở tầng app; cột `embedding vector(1536)` → kho vector ngoài hoặc cột JSON/BLOB.
- Partial unique index (`uq_one_current_per_doc`, `uq_one_effective_per_doc`, `idx_receipt_pending`) → MySQL không có partial index; mô phỏng bằng cột sinh + unique, hoặc kiểm tra ở service trong giao dịch.
- Rule `audit_log_no_update/delete` → chặn bằng quyền DB hoặc trigger MySQL.
- `gen_random_uuid()`/`gen_random_bytes()` → sinh UUID/token ở tầng app.
