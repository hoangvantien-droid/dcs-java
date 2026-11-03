# DCS-2 — Máy trạng thái vòng đời

> Suy từ code xử lý transition thật. Mô hình mới: **"đăng ký là ban hành luôn"** — không còn duyệt 2 lớp. Mỗi transition kèm guard, vai trò được bấm, side-effect (job/thông báo/kế thừa xác nhận đọc), và vị trí code.

## 1. Trạng thái phiên bản (`document_versions.status`)

Tập enum thật (CHECK ở `017_receipt_model.sql:30-39`, mở rộng từ `001`):

| Mã | Nhãn (labels.ts) | Ý nghĩa | Nhóm |
|----|------------------|---------|------|
| `cho_hoan_thien` | Nháp chờ hoàn thiện | Nháp Author lưu, chưa ban hành | mô hình mới |
| `da_ban_hanh` | Đã ban hành | Author đăng ký, ban hành thẳng — **trạng thái hiện hành chính** | mô hình mới |
| `thu_hoi` | Đã thu hồi | Author thu hồi (kèm lý do) | mô hình mới |
| `het_hieu_luc` | Hết hiệu lực | Bị phiên bản mới thay thế | mô hình mới |
| `cho_xac_nhan` | Chờ xác nhận | *mã cũ*, giữ cho dữ liệu/giai đoạn chuyển đổi | legacy |
| `tu_choi` | Bị trả lại | *mã cũ* | legacy |
| `da_dang_ky` | Đã xác nhận, chờ hiệu lực | *mã cũ* | legacy |
| `hieu_luc` | Đang hiệu lực | *mã cũ* (coi là hiện hành để dữ liệu cũ không vỡ) | legacy |

"Hiện hành" là nguồn DUY NHẤT ở `src/domain/lifecycle-status.ts` → `CURRENT_VIEW_STATUSES = ['da_ban_hanh','hieu_luc']` (**IND-05 — quyết định D6: đã BỎ `da_dang_ky`**). `isCurrentlyEffective`, `isEffectiveNow` (kèm điều kiện ngày hiệu lực đã tới — điểm 4). [mới kiểm chứng đọc code]

> **IND-05 (D6) — vì sao bỏ `da_dang_ky` khỏi "hiện hành":** `da_dang_ky` nghĩa là "đã đăng ký, chờ hiệu lực" nên không phải tài liệu đang có hiệu lực để site xem/áp dụng. Bốn định nghĩa "hiện hành" khác đã loại nó (`fn_search` + `v_current_documents` ở `020`; `catalog.currentVersionOf`; truy vấn bản hiện hành trong `verifyToken`); `CURRENT_VIEW_STATUSES` là ngoại lệ duy nhất → kéo về theo số đông. `da_dang_ky` là mã CŨ (luồng mới ban hành thẳng `da_ban_hanh`, không sinh `da_dang_ky` mới) nên chỉ ảnh hưởng dữ liệu legacy. `catalog.listVisibleDocuments`/`canViewVersion` IN-list Reader cũng rút còn `('da_ban_hanh','hieu_luc')`.

> **Lưu ý runtime vs comment:** `submitRegistration` chèn thẳng `status='da_ban_hanh'` (`src/domain/registration.ts:223`). Các mã cũ chỉ còn do dữ liệu lịch sử và hai hàm PL/pgSQL chuyển-hiệu-lực-theo-lịch (`fn_activate_due_versions`) còn xử lý `da_dang_ky → hieu_luc` cho dữ liệu cũ; luồng đăng ký mới KHÔNG tạo `da_dang_ky`/`cho_xac_nhan` nữa.

### 1.1 Bảng transition phiên bản

| Từ → Sang | Điều kiện (guard) | Vai trò | Side-effect | Vị trí code |
|-----------|-------------------|---------|-------------|-------------|
| (∅) → `cho_hoan_thien` | — (lưu nháp, không validate đầy đủ) | Author (`DC_AUTHOR`) | audit `sua_truoc_khi_nop` | `registration.ts:saveDraft:270-310` |
| (∅) → `da_ban_hanh` | `canRegisterDocuments`; `validateForSubmit` qua; ≥1 site nhận | Author | INSERT version; `publishToSites` sinh receipt `cho_phan_hoi`; audit `nop_dang_ky`; (nếu supersede nhỏ) sao chép ack; auto bóc tách best-effort | `registration.ts:154-262`, `routes/documents.ts:160-237` |
| `cho_hoan_thien` → `cho_hoan_thien` (sửa nháp) | là người đăng ký + status nháp | Author | tạo bản mới, xóa bản nháp cũ (giữ audit) | `routes/documents.ts:~500-560` |
| (bản gốc) `da_ban_hanh`/`hieu_luc`/`da_dang_ky` → `het_hieu_luc` | đang ban/ra bản thay thế (supersede) | Author (qua đăng ký bản mới) | audit `chuyen_het_hieu_luc` + `superseded_by`; **điểm 1** giữ `uq_one_current_per_doc` | `registration.ts:197-207` |
| `da_ban_hanh` → `thu_hoi` | là người đăng ký; bắt buộc lý do; **MỞ**: cho thu hồi kể cả khi mọi site đã chấp nhận | Author | audit `thu_hoi` (kèm tally + affected_site_ids); loại khỏi hàng chờ phản hồi + badge (điểm 5) | `receipt.ts:recallByAuthor:565-607` |
| `da_ban_hanh` → (xóa) | là người đăng ký; chưa site nào chấp nhận | Author | audit `xoa_ban_hanh`; DELETE version (receipt cascade) | `receipt.ts:deleteUnacceptedVersion:544-563` |
| `da_dang_ky` → `hieu_luc` | đến ngày hiệu lực (dữ liệu cũ / gói) | hệ thống (job) | bản cũ → `het_hieu_luc` cùng giao dịch; đóng rà soát treo; kế thừa ack nếu `nho`; audit `chuyen_hieu_luc` | `fn_activate_due_versions` (`026_lifecycle_sync.sql:48-180`) |

**Điểm 9 (đã vá):** `fn_activate_due_versions` **không còn** khối tự chuyển tài liệu quá `expiry_date` sang `het_hieu_luc` — hết hạn **chỉ cảnh báo** (banner detail + preset `expired`), không tự đổi trạng thái (`026_lifecycle_sync.sql:155-157`). Chi tiết rule ở DCS-5.

### 1.2 Sửa metadata tại chỗ (không đổi trạng thái)

| Thao tác | Guard | Vai trò | Side-effect | Vị trí code |
|----------|-------|---------|-------------|-------------|
| Sửa metadata (Loại 1 — sai đăng ký) | status `da_ban_hanh`; là người đăng ký; **chưa site nào chấp nhận** (`assertNotAcceptedLocked`); không đặt ngày hiệu lực về quá khứ (điểm 10); giữ `expiry_date` khi không gửi lại, chỉ xóa khi gửi sentinel rỗng (điểm 10) | Author | UPDATE metadata; audit `sua_dang_ky` | `receipt.ts:editVersionMetadata:482-524` |
| Phát hành thêm / phát hành lại site (**+ ngày hiệu lực riêng**) | status `da_ban_hanh`; là người đăng ký; tôn trọng khóa metadata (điểm 6, dùng chung `assertNotAcceptedLocked`); chặn nếu có `tu_choi+sai_noi_dung` (điểm 7); chỉ site trong scope; ngày riêng khai (nếu có) ≥ hôm nay VN (`publish.effective_past`) và ≥ `issued_date` (`publish.effective_before_issued`) | Author | site mới → receipt `cho_phan_hoi` kèm `site_effective_date`; site từ chối `sai_dang_ky` → mở lại `cho_phan_hoi` + cập nhật `site_effective_date`; audit `ban_hanh` | `receipt.ts:publishAdditionalSites` |

> **Luồng "thêm site ngày khác" (D3 — DCS-2/3/4):** một tài liệu đã ban hành ở site A, sau đó hiệu lực ở site B vào ngày khác, KHÔNG mở lại form đăng ký và KHÔNG tạo phiên bản/tài liệu mới. Author vào **Tài liệu của tôi → Tình trạng ban hành** (`issuance-status.eta`) → khối "Phát hành thêm site", chọn site và nhập **ngày hiệu lực riêng** (`<input name="eff_<site_id>">`, để trống = dùng ngày gốc), POST `/my-documents/:id/publish-sites` → `publishAdditionalSites`. [mới kiểm chứng đọc code]

## 2. Trạng thái receipt theo site (`document_receipt.status`)

Enum: `cho_phan_hoi`, `chap_nhan`, `tu_choi` (CHECK `017_receipt_model.sql:60-61`). `response_type ∈ {sai_dang_ky, sai_noi_dung}` bắt buộc khi `tu_choi` (`:69-72`, `chk_receipt_reject_type/_note`).

| Từ → Sang | Điều kiện (guard) | Vai trò | Side-effect | Vị trí code |
|-----------|-------------------|---------|-------------|-------------|
| (∅) → `cho_phan_hoi` | ban hành / phát hành thêm; idempotent theo (version, site) | Author (hệ thống sinh) | INSERT receipt kèm `site_effective_date` (NULL khi không khai riêng → coalesce ngày gốc); audit `ban_hanh` | `receipt.ts:publishToSites` |
| `cho_phan_hoi` → `chap_nhan` | DC Site đúng site (`siteIdsWithDocControl`); version KHÔNG `thu_hoi`/`het_hieu_luc` (điểm 5 + IND-01); ngày hiệu lực ĐÃ KHAI cho site (`coalesce(site_effective_date, effective_date)`) ≤ hôm nay giờ VN (điểm 4); `apply_date` ≥ ngày hiệu lực gốc (điểm 8) | DC Site (`DC_SITE_DOC_CONTROL`) | ghi `apply_date = coalesce(site_effective_date, effective_date)` (**D2: KHÔNG dùng today()**); audit `chap_nhan_nhan`; nếu tự-phản-hồi (người đăng ký = người phản hồi) → thêm audit `tu_phan_hoi_canh_bao` | `receipt.ts:acceptReceipt` |
| `cho_phan_hoi` → `tu_choi` | DC Site đúng site; bắt buộc loại + lý do | DC Site | ghi `response_type` + `response_note`; `apply_date`=NULL; audit `tu_choi_nhan` (+ cảnh báo tự-phản-hồi) | `receipt.ts:rejectReceipt:435-471` |
| `tu_choi(sai_dang_ky)` → `cho_phan_hoi` | phát hành lại sau khi Author sửa metadata; chưa ai chấp nhận | Author | reset response_*; cập nhật lại `site_effective_date` theo ngày khai (NULL nếu không khai); audit `ban_hanh` (action `re_publish`) | `receipt.ts:publishAdditionalSites` |

**Chặn chấp nhận sau thu hồi / hết hiệu lực (điểm 5 + IND-01):** `assertCanRespond` ném `receipt.recalled` khi `version_status='thu_hoi'`, hoặc `receipt.superseded` khi `het_hieu_luc` (bản bị bản mới thay thế) — dùng chung helper thuần `isReceiptBlockingVersionStatus`. `pendingReceiptsForSites`/`pendingReceiptCountForSites` loại version `NOT IN ('thu_hoi','het_hieu_luc')` khỏi hàng chờ và badge. [mới kiểm chứng đọc code — `receipt.ts`, `lifecycle-status.ts`]

> **IND-01 — vì sao thêm `het_hieu_luc`:** trước đây hàng chờ/badge + `assertCanRespond` chỉ loại `thu_hoi`; phiếu `cho_phan_hoi` của bản đã bị supersede (`het_hieu_luc` — `submitRegistration`) còn trong hàng chờ và vẫn chấp nhận được. Nay chặn cả hai trạng thái khóa.

**Chặn trước ngày hiệu lực (điểm 4):** `assertCanRespond` (mode accept) ném `receipt.before_effective` khi `coalesce(site_effective_date, effective_date) > hôm nay` (so với **giờ VN** qua `todayVN()`). [mới kiểm chứng đọc code — `receipt.ts`]

## 3. Trạng thái report bất thường (`document_issue_report.status`)

Enum: `moi`, `chuyen_phan_hoi`, `bac_bo`, `bao_sai_noi_dung_sau_chap_nhan` (CHECK `027_report_content_error_after_accept.sql:18-21`; cột nới `VARCHAR(40)` ở `027:13`).

| Từ → Sang | Điều kiện | Vai trò | Side-effect | Vị trí code |
|-----------|-----------|---------|-------------|-------------|
| (∅) → `moi` | là tài khoản có site (Dept chính); mọi vai đọc trừ Admin-thuần (`canReportIssue`) | Reader / Author / DC Site | audit `bao_bat_thuong` | `issue-report.ts:createIssueReport:35-69` |
| `moi` → `chuyen_phan_hoi` | DC Site đúng site; receipt của site đang `cho_phan_hoi` | DC Site | receipt → `tu_choi` cùng giao dịch; audit `chuyen_report_phan_hoi` + `tu_choi_nhan` | `issue-report.ts:convertReportToRejection:170-192` |
| `moi` → `bao_sai_noi_dung_sau_chap_nhan` | DC Site; receipt đã `chap_nhan`; loại `sai_noi_dung` (**điểm 7**) | DC Site | receipt **GIỮ** `chap_nhan` (không đảo ngược); audit `bao_sai_noi_dung_sau_chap_nhan` | `issue-report.ts:148-167` |
| `moi` → (CHẶN) | receipt đã `chap_nhan`; loại `sai_dang_ky` (**điểm 7**) | DC Site | ném `report.already_accepted` (phải thu hồi + bản mới) | `issue-report.ts:140-147` |
| `moi` → `bac_bo` | DC Site; bắt buộc lý do | DC Site | audit `bac_bo_report` | `issue-report.ts:dismissReport:196-208` |

> **Điểm 7 — vì sao đổi:** trước đây `convertReportToRejection` đóng report + ghi nhật ký từ chối nhưng `UPDATE receipt ... AND status='cho_phan_hoi'` là no-op khi receipt đã `chap_nhan` → lệch dữ liệu. Nay phân luồng rõ: `sai_dang_ky` sau chấp nhận bị chặn; `sai_noi_dung` sau chấp nhận đi trạng thái report riêng, receipt không đổi (`027_report_content_error_after_accept.sql:5-13`).

## 4. Trạng thái tổng hợp mức phiên bản (suy ra, không lưu)

`aggregateStatusOf(tally)` ở `src/domain/lifecycle-status.ts:98-104` suy từ số liệu receipt:

| Mã | Điều kiện | Nhãn |
|----|-----------|------|
| `chua_phat_hanh` | `total = 0` | Chưa phát hành |
| `tranh_chap` | `rejected > 0` (ưu tiên cao nhất) | Có site báo sai sót |
| `toan_bo` | `accepted = total` | Toàn bộ đã áp dụng |
| `mot_phan` | `accepted > 0` | Một phần đã áp dụng |
| `dang_cho` | còn lại | Đang chờ phản hồi |

`aggregateDisplay` (`labels.ts:149-168`) tách `tranh_chap` theo loại từ chối: có `sai_noi_dung` → "Có site báo sai nội dung" (đỏ); chỉ `sai_dang_ky` → "Có site báo sai đăng ký" (amber).

## 5. Trạng thái gói ban hành & rà soát (tóm tắt — chi tiết DCS-5)

- `release_packages.status ∈ {dang_mo, da_kich_hoat, da_giai_the}` (`001_schema.sql:197`). Kích hoạt nguyên tử khi mọi thành viên `da_dang_ky` (`fn_activate_due_versions` đường 2).
- `periodic_reviews.status ∈ {dang_ra_soat, treo_cho_ban_moi, da_dong, huy_bo}`; verdict `giu_nguyen/can_sua/huy_bo` (`fn_record_review_verdict`). `can_sua` → `treo_cho_ban_moi` (tự đóng khi bản mới lên hiệu lực).

## 6. Trạng thái bản in (`printed_copies.recall_status`)

`dang_luu_hanh | da_thu_hoi | khong_ro` (`001_schema.sql`). Thu hồi bản in tại hiện trường: `recallPrintedCopy` → `da_thu_hoi`, audit `thu_hoi_ban_in` (`printing.ts:171-193`, idempotent).
