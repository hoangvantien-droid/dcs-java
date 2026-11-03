# DCS-7 — Nguyên tắc trình bày & Điểm mở

## 1. Nguyên tắc trình bày

- Mọi mục trong bộ tài liệu DCS-1→DCS-10 **kèm tham chiếu `file:dòng`** (hoặc `file:hàm`) trỏ vào cây mã đã merge (nhánh `dcs-persite-effective-and-fixes`, gồm per-site effective + timezone VN + bug Nhóm A). **Không suy diễn** ngoài code.
- Code là **nguồn sự thật duy nhất**. Khi comment/docstring mâu thuẫn với hành vi runtime, tài liệu theo **hành vi runtime** và ghi chú lại (ví dụ ở DCS-2 §1 về trạng thái `da_dang_ky` chỉ còn do dữ liệu cũ).
- Enum trạng thái và mã nghiệp vụ dùng **đúng giá trị trong code/DB** (không đổi tên); nhãn tiếng Việt lấy từ `src/views/labels.ts` (nguồn duy nhất ánh xạ mã → nhãn).
- **Tổng số migration thực trên nhánh: 28** (`001`…`028`), bao gồm `026_lifecycle_sync.sql`, `027_report_content_error_after_accept.sql`, và `028_site_effective_date.sql` (F3). Mọi con số khác (16, 25, 27) trong tài liệu/README cũ là **lạc hậu** — xem DCS-8 §1.
- **F5 — nhãn kiểm chứng:** mỗi khẳng định về code gắn một trong: **[đã kiểm chứng test DB]** (test tích hợp Postgres — chạy VPS), **[mới kiểm chứng đọc code]** (đọc code đã merge; integration test tự skip vì máy không có Postgres local), **[chưa kiểm chứng runtime]** (suy từ code, chưa chạy xác nhận đầu-cuối).

## 1b. Quyết định thiết kế đợt sửa per-site + fixes (D1–D7)

| # | Quyết định | Tóm tắt | Vị trí |
|---|-----------|---------|--------|
| **D1** | Per-site effective (phương án 2a) | Giữ `effective_date` làm ngày GỐC; thêm `document_receipt.site_effective_date` (NULL=coalesce). Không nhân bản tài liệu/phiên bản. | `028`, `receipt.ts:effectiveForSite` |
| **D2** | `apply_date = site effective` | `acceptReceipt` đặt `apply_date = coalesce(site_effective_date, effective_date)`, KHÔNG today(). "Chấp nhận" = đồng ý ngày đã khai. | `receipt.ts:acceptReceipt` |
| **D3** | Khai ngày riêng khi THÊM site | Site đầu (lúc `/register`) coalesce ngày gốc; thêm site về sau qua `publishAdditionalSites` + ô `eff_<site_id>`. KHÔNG mở lại form đăng ký. | `documents.ts` publish-sites, `issuance-status.eta` |
| **D4** | Ràng buộc ngày | `site_effective_date >= issued_date` = trigger DB (`028`); `>= hôm nay VN` = domain (mốc runtime không hợp ràng buộc lưu bền). | `028`, `receipt.ts`/`registration.ts` |
| **D5** | Timezone VN | `todayVN()` (Intl, không phụ thuộc `process.env.TZ`) + TimeZone session Postgres = `Asia/Ho_Chi_Minh`. Hết lệch biên 00:00–07:00. | `clock.ts`, `client.ts` (xem DCS-6 §3b) |
| **D6** | IND-05 bỏ `da_dang_ky` | `CURRENT_VIEW_STATUSES = ['da_ban_hanh','hieu_luc']`; kéo về theo 4 định nghĩa "hiện hành" khác. Chỉ ảnh hưởng dữ liệu legacy. | `lifecycle-status.ts`, `catalog.ts` |
| **D7** | IND-06 Admin ngoài luồng | Lệch tài liệu, KHÔNG phải bug: `requireDocFlow` chặn Admin-thuần 403; nhánh Admin trong catalog chỉ cho dual-role. Sửa DCS-9 §3. | `auth.ts`, `permissions.ts`, `catalog.ts` |

## 2. Spec `.kiro/specs` lạc hậu so với code (đánh dấu)

| Spec | Trạng thái so với code |
|------|------------------------|
| `author-edit-recall-unacknowledged-docs` | ⚠️ mới có khung (requirements/design/tasks) — phần "sửa/thu hồi tài liệu chưa xác nhận" chưa hoàn thiện nội dung; một phần hành vi đã nằm trong `receipt.ts` (edit/recall/delete theo điều kiện receipt) nhưng spec chưa mô tả khớp mô hình mới |
| `reference-sample-management` | ❌ chưa có code (mẫu chuẩn FR-05) — điểm 11 |
| `catalog-excel-export` | ✅ đã hiện thực (`xlsx.ts`, export ở catalog/my-documents) |
| `publish-additional-sites` | ✅ đã hiện thực (`publishAdditionalSites`) — nay có thêm **ngày hiệu lực riêng per-site** (D1/D3, `028`) |
| `review-visibility-dc-site` | ✅ đã hiện thực (`reviewBacklogForSites`) |
| `expired-document-visibility` | ✅ đã hiện thực (preset `expired`, banner — điểm 9) |
| `dispute-label-split` | ✅ đã hiện thực (`aggregateDisplay` tách sai nội dung/sai đăng ký) |
| `iso-terminology-mobile-ui`, `date-label-clarity`, `friendly-error-messages`, `eta-render-500-asi`, `document-markdown-fulltext`, `dcs-mcp-server`, `dcs-p1-p2-test-fixes`, `author-recall-expansion` | ✅ phần lớn đã hiện thực (nhãn, mobile, toast lỗi, markdown fulltext, MCP, các bugfix P1/P2, mở quyền thu hồi) |

Các spec `.kiro` mô tả **mô hình cũ (duyệt 2 lớp, số migration cũ)** phải coi là lạc hậu; tài liệu nghiệp vụ chuẩn là bộ DCS-1→DCS-10 này (sinh từ code đã vá).

## 3. Điểm mở (chỗ chưa rõ / chưa implement / thủ công)

### Điểm 11 — Mẫu chuẩn (FR-05/FR-06): mâu thuẫn chỉ trên giấy, CHƯA có code
- Spec `reference-sample-management` yêu cầu: (a) tạo yêu cầu lấy mẫu mới → có chuyển mẫu sang trạng thái "Cần thay mẫu" không; (b) đường quay "Cần thay mẫu" → "Có hiệu lực" khi tạo yêu cầu nhầm; lead time; ngày cảnh báo.
- **Hiện trạng code:** không có bảng, route, hay domain nào (grep `reference_sample|can_thay_mau|lay_mau|mau_chuan|FR-05` trong `src/` = 0 kết quả). Mâu thuẫn FR-05 (lấy mẫu mới) vs FR-06 (bảng trạng thái) là **vấn đề trên giấy, chưa đụng code**. Cần chốt một đáp án giữa FR-05 và bảng trạng thái TRƯỚC khi implement.

### Rotate secret thật (thủ công)
- `.env` thật từng bị kèm gói phát hành (lộ mật khẩu DB + `SESSION_SECRET`). Code đã loại `.env` khỏi gói (`.gitignore`, chỉ phát `.env.example`). **Việc rotate secret cho người dùng là thao tác THỦ CÔNG** (đổi mật khẩu DB + sinh `SESSION_SECRET` mới ≥32 ký tự + khởi động lại) nếu gói từng gửi ra ngoài — không có cơ chế tự động trong code.

### Sửa/thu hồi tài liệu chưa xác nhận
- Spec `author-edit-recall-unacknowledged-docs` mới có khung. Hành vi hiện tại dựa trên điều kiện **receipt** (`accepted`/`rejected`) chứ không phải "đã xác nhận đọc" (ack). Cần làm rõ khung spec khớp mô hình receipt mới hay bổ sung điều kiện theo ack.

### IND-07 — `document_links` + `distribution_matrix` thiếu API/UI đầy đủ
- **`document_links`** (nhãn liên kết QDMS, `001`) có bảng + được nạp hiển thị ở `detail.eta` (`catalog.ts:loadLinks`) và view tra ngược `v_clause_references`, nhưng **KHÔNG có route/UI để tạo/sửa/xóa nhãn** trong DCS — nhãn phải nạp từ nguồn khác (QDMS/nạp dữ liệu). Chỉ-đọc ở phía DCS.
- **`distribution_matrix`** (`001`) tồn tại trong lược đồ để tính "ai phải đọc", phục vụ qua `v_distribution_resolved`/`v_ack_pending`, nhưng **KHÔNG có API/UI quản lý ma trận phân phối trực tiếp** — ma trận suy từ scope + bộ phận/nhóm, không có màn CRUD riêng.
- **Hệ quả:** cần chốt nghiệp vụ: (a) DCS có cần màn quản lý nhãn liên kết + ma trận phân phối không, hay giữ nguồn ngoài (QDMS/L1); (b) nếu cần, bổ sung route + domain + view. Hiện là **điểm mở chưa implement**. [mới kiểm chứng đọc code — grep route/view cho `document_links`/`distribution_matrix` = chỉ đọc hiển thị, không có CRUD]

### Chỗ code chưa rõ / chưa đầy đủ
- **Trạng thái legacy còn trong CHECK/union:** `cho_xac_nhan`, `tu_choi`, `da_dang_ky`, `hieu_luc` còn giữ cho dữ liệu/giai đoạn chuyển đổi (comment "Task 9 dọn"). Luồng mới không tạo chúng; cần một đợt dọn sau khi chắc không còn dữ liệu tham chiếu.
- **`document_site_rollout` (migration 011)** còn tồn tại và được dùng để lọc danh mục cũ, trong khi mô hình mới dùng `document_receipt`. Hai khái niệm song song — cần dọn `rollout` khi chắc chắn không còn phụ thuộc.
- **Gói ban hành vẫn theo trạng thái cũ:** `attachToPackage` chỉ nhận `cho_xac_nhan/da_dang_ky` (`package.ts:97-99`) và kích hoạt gói đi qua `da_dang_ky → hieu_luc`. Luồng gói **chưa đồng bộ hoàn toàn** với mô hình `da_ban_hanh` — nếu nghiệp vụ cần gói cho mô hình mới, phải cập nhật `package.ts` + `fn_activate_due_versions`.
- **`home.ta` "rejectedMine"** liệt kê mọi receipt `tu_choi` của Author (gồm cả `sai_dang_ky` lẫn `sai_noi_dung`) — chưa tách rõ ưu tiên; không sai dữ liệu nhưng có thể gây nhiễu.
- **MCP server (`routes/mcp.ts`)** còn phần dựng server/tool/resource được xây dần theo task; phần hằng số + phân giải danh tính đã có, nhưng cần kiểm tra mức độ hoàn thiện của tool khi port.
- **Bóc tách nội dung** chạy best-effort; `parse_status`/`index_status` có thể `that_bai/kem_tin_cay` mà không chặn ban hành — tìm kiếm/AI với tài liệu đó sẽ hạn chế.

## 4. Lưu ý mâu thuẫn runtime vs comment đã ghi nhận
- DCS-2 §1: comment các mã cũ trong CHECK gợi ý chúng còn dùng; runtime (`submitRegistration`) chỉ tạo `da_ban_hanh` — mã cũ chỉ còn do dữ liệu lịch sử + hàm chuyển-hiệu-lực-theo-lịch.
- `catalog.ts` comment vài chỗ còn nhắc "người duyệt/release" (mô hình cũ) nhưng quyền thực thi theo mô hình mới (người đăng ký / canView). Tài liệu theo hành vi runtime.
