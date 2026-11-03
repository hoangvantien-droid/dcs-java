# DCS-4 — UI specs (theo từng màn hình)

> Tối ưu **desktop ≥1280px**; mobile ≤768px có drawer điều hướng thuần CSS (`layout.eta`). Nhãn tiếng Việt lấy từ `src/views/labels.ts` (nguồn duy nhất). Nút/hành động ẩn hẳn theo vai trò (`middleware/nav.ts`).

## 0. Khung chung — thanh điều hướng & menu (`layout.eta`)

**Topbar:** brand "DCS / Document Control System"; ô tìm (`GET /search`, gợi ý htmx `GET /search/suggest` keyup 250ms → `#suggest-box`); avatar + tên + Đăng xuất.

**Sidebar — ẩn/hiện theo cờ `nav` (`middleware/nav.ts:36-69`):**

| Mục menu | Đường dẫn | Điều kiện hiện | Badge |
|----------|-----------|----------------|-------|
| Trang chủ | `/` | luôn | — |
| Danh mục tài liệu | `/documents` | `inDocFlow` (Author/DC Site/Reader) | — |
| Tài liệu của tôi | `/my-documents` | `canSeeMyDocuments` (= `canRegister` = có `DC_AUTHOR`) | — |
| Chờ site phản hồi | `/site-receipts` | `isSiteDocControl` | **`nav.pendingReceipts`** khi > 0 |
| Báo bất thường (site) | `/issue-reports` | `isSiteDocControl` | — |
| Xem xét định kỳ | `/reviews` | `canRegister || isSiteDocControl` | — |
| Gói ban hành | `/packages` | `canRegister` | — |
| Nhóm chức năng / Quản trị tổ chức / người dùng / Vai trò / Ghi vết | `/functional-groups`, `/admin/*`, `/audit` | `isAdmin` | — |

**Badge số đếm chờ (`pendingReceipts`):** `pendingReceiptCountForSites` (`receipt.ts:252-261`):
```
COUNT(document_receipt) WHERE status='cho_phan_hoi'
  AND version.status <> 'thu_hoi'         ← LOẠI TRỪ: tài liệu đã thu hồi (điểm 5)
  AND site_id ∈ các site người dùng giữ vai DC_SITE_DOC_CONTROL
```
Admin-thuần đứng ngoài luồng (menu quản trị riêng); mục tài liệu ẩn (`nav.inDocFlow=false`).

Lỗi nghiệp vụ hiển thị qua `#toast-zone` (htmx 4xx → fragment toast tự ẩn sau 5s); non-htmx → trang `error.eta` (`http-errors.ts`).

---

## 1. Đăng nhập (`/login`, `login.eta`)
- Trường: `email` (text), `password` (password). Lỗi → chuỗi `error` đỏ. Đã đăng nhập → 302 `/`.

## 2. Trang chủ (`/`, `home.eta`)
- **Mục đích:** "hôm nay tôi cần làm gì".
- DC Site: danh sách receipt chờ site phản hồi (link `/site-receipts/:id`).
- Author: tài liệu mình ban hành bị site báo sai sót (receipt `tu_choi`).

## 3. Danh mục tài liệu (`/documents`, `catalog.eta`) — list view

**Mục đích:** tra cứu tài liệu trong phạm vi người dùng; lọc giữ trên URL để chia sẻ/bookmark.

**Cột bảng:** Số hiệu (link detail) · PB · Tiêu đề · Loại · Phạm vi (`scopeLabel`) · Trạng thái (badge `status-<code>` + `docStatusLabel`) · Hiệu lực · File PDF (Xem PDF / Tải — chỉ khi `da_ban_hanh`/`da_dang_ky`/`hieu_luc`).

**Bộ lọc (form GET):**
- Quick filters: Đang hiệu lực (`preset=hieu_luc`), Mới 30 ngày (`new30`), Sắp hết hạn 30 ngày (`expiring30`), **Quá hạn rà soát (`overdue`)**, Đã hết hạn (`expired`), Xóa lọc.
- `q` (text) · Preset (select) · Trạng thái (select: Đã ban hành / Đã thu hồi / Hết hiệu lực / Nháp) · Loại (`doc_type`) · Nguồn · Hiệu lực từ/đến (date) · **Site áp dụng** (`site_id`) · Tình trạng tiếp nhận (`rollout_status`: Chờ phản hồi/Đã tiếp nhận/Đã từ chối — ánh xạ receipt) · Sắp xếp (Hiệu lực mới nhất / Ban hành mới nhất / Sắp hết hạn / Số hiệu A-Z).
- Preset **`overdue` = "Quá hạn xem xét"**: `next_review_date < CURRENT_DATE` (`catalog.ts domain:127-131`).

**Scope/vai trò:** `listVisibleDocuments` lọc TRƯỚC trong truy vấn (`catalog.ts domain:48-137`): tài liệu mình nộp, hoặc trong phạm vi (group/cây tổ chức), hoặc là Admin. **Reader (`readOnlyViewer`)**: chỉ `da_ban_hanh/hieu_luc/da_dang_ky` VÀ `effective_date <= CURRENT_DATE` (điểm 4 — không thấy bản tương lai).

**Nút:** Đăng ký tài liệu (chỉ `canRegister`) → `/register`; Xuất danh mục Excel (chỉ `canRegister || isSiteDocControl`) → `/documents/export.xlsx` giữ nguyên querystring lọc.

## 4. Chi tiết tài liệu (`/documents/:id`, `detail.eta`)

**Mục đích:** xem PDF + metadata + áp dụng theo site + ack + in + báo bất thường.

**Hiển thị:** tiêu đề, badge trạng thái + phạm vi; dải info (Loại/Nguồn, Phạm vi, Ngày ban hành, Ngày hiệu lực, Xem xét tới); iframe PDF (`/view` hoặc `/preview` nếu là người đăng ký xem bản chưa phát hành).

**Banner cảnh báo (không đổi trạng thái):**
- `het_hieu_luc` → đỏ "ĐÃ HẾT HIỆU LỰC" + link bản đang áp dụng.
- `thu_hoi` → đỏ "ĐÃ THU HỒI" + lý do.
- `qua_han_xem_xet` → vàng "QUÁ HẠN RÀ SOÁT".
- `qua_han_su_dung` (quá `expiry_date`) → đỏ "ĐÃ QUÁ NGÀY HẾT HẠN (…)" (điểm 9 — chỉ cảnh báo).

**Nút/hành động:**
| Nút | Vai trò thấy | Gọi API | Confirm | Sau khi xong |
|-----|--------------|---------|---------|--------------|
| Mở PDF / Tải PDF | có quyền xem | `/view`, `/download` | — | — |
| In bản sao có kiểm soát | version hiện hành (`da_ban_hanh/da_dang_ky/hieu_luc`) | POST `/documents/:id/print` (form `site_id?`, `location?`) | — | `print-result.eta` |
| Báo bất thường | `nav.canReport` | POST `/documents/:id/report` (`note*`) | — | về detail (toast) |
| Bóc tách nội dung từ tệp | người đăng ký, khi chưa có sections | POST `/documents/:id/process` | — | reload detail |
| Xác nhận đã đọc | thuộc diện phải đọc + chưa xác nhận (`ackState.show`) | POST `/documents/:id/acknowledge` | — | reload (hiện "Đã xác nhận", có ghi "kế thừa" nếu inherited) |
| Thu hồi bản in | người có quyền xem bản, bản `dang_luu_hanh` | POST `/documents/:id/print/recall` (`print_id`) | "Thu hồi bản in này?" | reload |

**Panel gập:** Nhãn liên kết (chip) · Áp dụng theo site (ma trận receipt) · Bản sao có kiểm soát đã in (bảng) · Lịch sử & ghi vết (link `/audit?object_id=`).

## 5. Đăng ký tài liệu (`/register`, `register.eta`)

**Mục đích:** cổng DUY NHẤT đưa tài liệu vào `da_ban_hanh` (hoặc lưu nháp `cho_hoan_thien`). Hiển thị progress-steps 3 khối (Tài liệu / Hiệu lực / Phân loại).

**Trường:**
| Trường | Loại | Nguồn | Bắt buộc | Validate / disable |
|--------|------|-------|----------|---------------------|
| `doc_id` | text | người nhập | ✓ | cặp `doc_id`+`version_no` chưa tồn tại (`reg.duplicate`) |
| `doc_type` | select | `DOC_TYPES` | — | SOP/WI/Form/Policy/External |
| `origin` | select | enum | — | ngoại bộ → hiện "Ngày hiệu lực pháp lý" |
| `scope_level` | select | group/bu/site/dept | — | con trỏ tương ứng bắt buộc (`reg.scope_*`) |
| `scope_bu_id/site_id/dept_id` | select | org | theo cấp | chuẩn hoá về đúng 1 con trỏ (`normalizeDocumentScope`) |
| `file` (PDF) | file | upload | ✓ | `reg.file` nếu thiếu; lưu SHA-256 |
| `site_ids[]` | checkbox nhiều | sites | ✓ (≥1) | `reg.no_site` nếu rỗng — ban hành thẳng tới site chọn |
| `version_no`, `title`, `issued_date` | text/date | — | ✓ | — |
| `external_effective_date` | date | — | ngoại bộ ✓ | `reg.ext_date` |
| `effective_date` | date | — | ✓ | `reg.effective_past` nếu < hôm nay |
| `review_cycle_months` | number | — | mặc định 12 | > 0 |
| `expiry_date` | date | — | — | `reg.expiry` nếu ≤ hiệu lực |
| `owner_department_id` | select | depts | ✓ | htmx đếm người nhận realtime (`/register/recipient-count`) |
| `reg_mode` (Tạo mới/Update) | select | client | — | Update → mở khối supersede |
| `supersedes_version_id` | select | bản `da_ban_hanh/hieu_luc/da_dang_ky` | Update ✓ | `reg.supersedes` |
| `change_significance` | select (Lớn/Nhỏ) | — | Update ✓ | `reg.change_sig`; cảnh báo "nhỏ = không phải xác nhận lại" |
| `change_summary` | text | — | nếu Lớn ✓ | `reg.change_summary` (chặn client + server) |

**Nút:** "Ban hành tài liệu" (POST `/register`) · "Lưu nháp" (POST `/register/draft`, formaction).
Checklist bên phải: Đủ trường bắt buộc / Đã chọn tệp PDF / Khai mức độ thay đổi nếu thay thế.

## 6. Tài liệu của tôi (`/my-documents`, `my-documents.eta`)

**Mục đích:** Author theo dõi tài liệu mình nộp + tình trạng ban hành theo site.
**Cột (nguồn `assembleMyDocuments`):** Số hiệu · Phiên bản · Tiêu đề · Trạng thái (`docStatusLabel`) · Site xác nhận (`accepted/total`) · Tình trạng ban hành (`aggregateDisplay` — tách sai nội dung/sai đăng ký) · Lý do từ chối. Export Excel 7 cột khớp y hệt.

## 7. Tình trạng ban hành (`/my-documents/:id/issuance`, `issuance-status.eta`)

**Mục đích:** ma trận phản hồi theo site + hành động Author. Chỉ người đăng ký (403 nếu không).

- Banner: có `sai_noi_dung` → cảnh báo thu hồi + bản mới; có `sai_dang_ky` → info sửa metadata.
- Trạng thái tổng hợp (`aggregateDisplay`) + đếm Chấp nhận/Từ chối/Chờ/Tổng.
- Ma trận `site-response-matrix.eta` (component).
- **Hành động (ẩn/hiện theo điều kiện domain):**
  - Sửa thông tin đăng ký / Thu hồi & ra bản mới — hiện khi `canEditOrRecall` (chưa ai chấp nhận HOẶC có từ chối).
  - Khi đã có site chấp nhận → khóa sửa trực tiếp, chỉ còn Thu hồi & ra bản mới.
  - Xóa bản ban hành — chỉ khi `canDelete` (chưa ai chấp nhận); confirm "Xóa bản ban hành này?".
  - Phát hành thêm site: fieldset "Thêm site mới" + "Phát hành lại (đã từ chối sai đăng ký)"; chặn nếu `blockedByContentReject`.

## 8. Sửa thông tin đăng ký (`/my-documents/:id/edit-metadata`, `edit-metadata.eta`)
- **Khóa khi đã có site chấp nhận:** `locked=true` → banner "Đã khóa", ẩn form (điểm 6).
- Trường **disable:** Số hiệu + Phiên bản (`disabled`). Sửa được: Tiêu đề, Ngày ban hành, Ngày có hiệu lực, Chu kỳ rà soát, Ngày hết hạn, Bộ phận sở hữu ("-- giữ nguyên --").
- Validate: không đặt ngày hiệu lực về quá khứ (`edit.effective_past`); không nhập `expiry_date` → giữ nguyên, chỉ xóa khi gửi rỗng (điểm 10).

## 9. Thu hồi (Author) (`/my-documents/:id/recall`, `author-recall.eta`)
- Trường `reason` (bắt buộc — `recall.reason`). Hiện tally tác động trước khi xác nhận. Chỉ status `da_ban_hanh`.

## 10. Chờ site phản hồi (`/site-receipts`, `site-receipts.eta`) — DC Site, list view
- Bảng 1 "Chờ site phản hồi": Số hiệu · Phiên bản · Tiêu đề · Site · **Cảnh báo** (site khác báo sai nội dung = đỏ / sai đăng ký = xám) · Thao tác (Mở phản hồi).
- Bảng 2 "Tài liệu bị thu hồi": Số hiệu · Tiêu đề · Lý do thu hồi · Thu hồi lúc (nhắc ngừng dùng/thu hồi bản in).
- `notControl=true` nếu chưa được gán site → banner thông tin.

## 11. Phản hồi một receipt (`/site-receipts/:id`, `receipt-respond.eta`)
- PDF nhúng (`object` → `/view`) + mục bóc tách.
- Cảnh báo tách vai nếu `selfRegistered`; cảnh báo nếu site khác báo sai nội dung/sai đăng ký.
- **Chấp nhận:** nút "Xác nhận tiếp nhận" (POST `/accept`, htmx → toast) — **ẩn** nếu `_recalled` (thu hồi) hoặc `_beforeEffective` (chưa tới ngày hiệu lực); thay bằng banner.
- **Từ chối:** `reject_type` (select: Sai thông tin đăng ký / Sai nội dung tài liệu) + `note*` (textarea). POST `/reject`.

## 12. Hộp report (`/issue-reports`, `issue-reports.eta`) — DC Site
- Report `moi` của site; mỗi report: Chuyển thành phản hồi (chọn loại + note) hoặc Bác bỏ (`review_note*`). `notControl` nếu không giữ site.

## 13. Xem xét định kỳ (`/reviews`, `reviews.eta`)
- **Author:** "Đang xem xét" (nút ghi kết luận: select `verdict` giu_nguyen/can_sua/huy_bo + `note` — bắt buộc nếu không giữ nguyên) + "Treo chờ bản mới".
- **DC Site:** bảng "Theo dõi xem xét (site bạn phụ trách)" **chỉ-đọc** (cột: Số hiệu, Tiêu đề, Hạn, Quá hạn); kết luận thuộc bộ phận sở hữu.

## 14. So sánh phiên bản (`/compare`, `compare.eta`)
- Query `from`, `to`. Hiện độ tin cậy (`la_phien_ban_dau_tien`/`boc_tach_kem_tin_cay`/`khong_co_muc_de_so`/`dang_tin`) + bảng diff mục (thêm/xóa/đổi nội dung/đổi tiêu đề). Ghi `xem_so_sanh` khi có `from`.

## 15. Tìm kiếm (`/search`, `search-results.eta`) — list view + rail lọc
- Rail lọc: `doc_type[]`, `origin`, `scope_level`, BU/Site/Dept cụ thể, hiệu lực từ/đến, `match_level[]`, sort, công tắc "bản lịch sử" (mặc định tắt). Lọc tức thì qua `/search/results` (htmx). Chip phản chiếu trạng thái lọc.

## 16. In — kết quả (`print-result.eta`) & Tra cứu QR (`/verify/:token`, `verify.eta`)
- `print-result`: hiện `verify_token`, QR SVG, verifyUrl.
- `verify` (CÔNG KHAI, màn nhỏ): CHỈ tình trạng — `con_hieu_luc`/`het_hieu_luc`/`thu_hoi`/`ban_in_thu_hoi`/`khong_ro`; kèm `?site_id=` → `chua_tiep_nhan`/`tu_choi_tiep_nhan`/`chap_nhan` + apply_date. Tuyệt đối không hiện nội dung tài liệu.

## 17. Admin (`/admin/org`, `/admin/users`, `/admin/roles`) & Ghi vết (`/audit`)
- Admin: form tạo BU/Site/Dept, người dùng (email/full_name/password), gán/xoá bộ phận, kích hoạt/vô hiệu/soft-delete, gán vai trò theo phạm vi. Nhãn vai trò qua `roleLabel`.
- Ghi vết: lọc theo `object_id`, `event_type`, khoảng ngày; chỉ Admin.
