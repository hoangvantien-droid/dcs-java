# DCS (bản Java) — Kịch bản kiểm thử chấp nhận (UAT)

Tài liệu này mô tả các **kịch bản UAT** cho bản Java (port GĐ2) của DCS, dùng để
nghiệm thu nghiệp vụ trước khi phát hành. Nguồn gốc kịch bản: tái dùng bộ UAT
bản TypeScript (`dcs/docs/UAT-KICH-BAN-KIEM-THU.md` + `dcs/test/uat/`), **tái
hiện trên bản Java** sao cho hành vi giống nhau. Không thêm nghiệp vụ mới.

- Mỗi ca ghi: **Mã ca**, **Vai trò**, **Tiền điều kiện**, **Các bước
  (Given/When/Then)**, **Kết quả mong đợi**, **Tham chiếu endpoint/controller**.
- Mô hình vai trò của bản Java (mới, theo migration 012/013/018): **4 vai** —
  `DC_ADMIN`, `DC_AUTHOR`, `DC_SITE_DOC_CONTROL` (theo site), `DC_READER`.
- Mô hình ban hành của bản Java là **ban hành thẳng + phiếu nhận theo site**
  (receipt model, migration 017): Author đăng ký = ban hành ngay; mỗi site có
  một **phiếu nhận (receipt)**; DC Site **chấp nhận / từ chối** phiếu. Ngày hiệu
  lực **độc lập theo từng site** (`site_effective_date`, migration 028).
- Mỗi ca có mã `[BẢO MẬT]` là kiểm soát an ninh, **bắt buộc đạt**.
- Cột **Test tự động** trỏ tới file/phương thức IT phủ ca đó (xem
  `app/src/test/java/com/dqh/dcs/uat/`). Ca không gắn test chỉ kiểm thủ công.

> **Khác biệt so với bản TS:** bản TS dùng mô hình 3 vai (`DC_DEPT_HEAD`) với
> luồng "chờ xác nhận/release" tập trung. Bản Java chuyển sang **receipt theo
> site** + **DC_SITE_DOC_CONTROL**. Do đó các ca nhóm "xác nhận/release" của TS
> được tái hiện thành các ca "DC Site chấp nhận/từ chối phiếu" ở đây; bản chất
> nghiệp vụ (hai người, đúng phạm vi, chống đua trạng thái, ngày hiệu lực) giữ
> nguyên.

---

## 0. Chuẩn bị môi trường & dữ liệu nền

Dữ liệu seed (theo `scripts/seed_matrix.mjs`, tái hiện trong test
`support/DbSupport.java`):

- 1 BU `MEAT`, 2 site `MML` + `HCM`, mỗi site một department QA.
- Tài khoản (mật khẩu chung cho test tự động: `matkhau-test-123`):
  - `author@mml.io` — `DC_AUTHOR` (group).
  - `reader@mml.io` — `DC_READER` (group), dept chính thuộc site MML.
  - `admin@mml.io` — `DC_ADMIN` (group) — **Admin thuần, ngoài luồng tài liệu**.
  - `dc_mml@mml.io` — `DC_SITE_DOC_CONTROL` site MML.
  - `dc_hcm@mml.io` — `DC_SITE_DOC_CONTROL` site HCM.

Khởi động: `GET /healthz` trả JSON trạng thái ok (`HealthController`).

---

## A. Đăng nhập, phiên, phân quyền theo vai trò

| Mã ca | Vai trò | Tiền điều kiện | Các bước (Given/When/Then) | Kết quả mong đợi | Endpoint/Controller | Test tự động |
|---|---|---|---|---|---|---|
| UAT-01 | Mọi vai | Có tài khoản hợp lệ | **Given** đang ở `/login`; **When** POST email + mật khẩu đúng; **Then** nhận cookie `dcs_sid` đã ký + điều hướng `/` | Đăng nhập thành công, cookie phiên được đặt | `POST /login` (`AuthController`) | `UatAuthFlowIT.dangNhapDungDatCookieVaVaoTrangChu` |
| UAT-02 `[BẢO MẬT]` | — | — | **Given** ở `/login`; **When** POST mật khẩu sai; **Then** không cấp cookie, báo lỗi chung | HTTP 401, thông điệp "Email hoặc mật khẩu không đúng" (không lộ email tồn tại) | `POST /login` (`AuthController` → `AuthService`) | `UatAuthFlowIT.dangNhapSaiKhongCapCookie` |
| UAT-03 `[BẢO MẬT]` | — | Chưa đăng nhập | **When** mở `/documents` không cookie; **Then** bị chuyển `/login` | HTTP 302 → `/login` | `GET /documents` (`Guards.requireUser`) | `UatAuthFlowIT.chuaDangNhapVaoDocumentsBiChuyenLogin` |
| UAT-04 `[BẢO MẬT]` | Admin thuần | Đăng nhập `admin@mml.io` | **When** Admin mở `/documents`; **Then** bị chặn 403 | HTTP 403 (Admin quản trị ngoài luồng tài liệu) | `GET /documents` (`Guards.requireDocFlow`) | `UatAuthFlowIT.adminThuanVaoDocumentsBi403` |
| UAT-05 | Author/Reader/DC Site | Đăng nhập bằng vai nghiệp vụ | **When** mở `/documents`; **Then** xem được danh mục theo phạm vi | HTTP 200, trang danh mục | `GET /documents` (`CatalogController`) | `UatAuthFlowIT.vaiNghiepVuVaoDocumentsOk` |
| UAT-06 | Mọi vai | Đã đăng nhập | **When** mở `/logout`; **Then** phiên bị huỷ, về `/login`; dùng lại session cũ không được | HTTP 302 → `/login`, phiên không phân giải lại | `GET /logout` (`AuthController`) | `UatAuthFlowIT.dangXuatHuyPhien` |
| UAT-07 `[BẢO MẬT]` | — | — | **When** gửi cookie `dcs_sid` chữ ký sai tới `/documents`; **Then** coi như vô danh | HTTP 302 → `/login` | `SessionAuthFilter` (`CookieSigner.unsign`) | `UatAuthFlowIT.cookieChuKySaiCoiNhuVoDanh` |

---

## B. Author đăng ký ban hành (ban hành thẳng) + chọn site + ngày hiệu lực

| Mã ca | Vai trò | Tiền điều kiện | Các bước (Given/When/Then) | Kết quả mong đợi | Endpoint/Controller | Test tự động |
|---|---|---|---|---|---|---|
| UAT-10 | Author | Đăng nhập `author@mml.io` | **Given** form đăng ký; **When** POST `/register` với doc_id, version, tiêu đề, tệp, 1 site, ngày hiệu lực ≥ hôm nay; **Then** tạo bản ban hành + sinh phiếu nhận cho site đã chọn | Điều hướng `/my-documents`; bản ở `da_ban_hanh`; 1 receipt `cho_phan_hoi` | `POST /register` (`DocumentController` → `RegistrationService`) | `UatAuthFlowIT.authorDangKyBanHanhTaoReceipt` |
| UAT-11 `[BẢO MẬT]` | Reader | Đăng nhập `reader@mml.io` | **When** mở `/register`; **Then** bị chặn | HTTP 403 "Không có quyền đăng ký ban hành tài liệu" | `GET /register` (`permissions.canRegisterDocuments`) | `UatAuthFlowIT.readerMoRegisterBi403` |
| UAT-12 | Author | — | **When** POST `/register` không tệp; **Then** bị chặn | HTTP 4xx, lỗi "Bắt buộc tải lên tệp PDF" | `POST /register` (`reg.file`) | (kiểm thủ công / phủ ở unit reg) |

---

## C. Ngày hiệu lực theo từng site (per-site effective) + phát hành thêm site

| Mã ca | Vai trò | Tiền điều kiện | Các bước (Given/When/Then) | Kết quả mong đợi | Endpoint/Controller | Test tự động |
|---|---|---|---|---|---|---|
| UAT-20 | Author | Bản đã ban hành cho site A, ngày hiệu lực gốc ở tương lai | **When** POST `/my-documents/{id}/publish-sites` thêm site B kèm `eff_<B>` khác ngày site A; **Then** thêm phiếu cho site B với ngày hiệu lực riêng của B | Site B có receipt + `site_effective_date` = ngày khai cho B | `POST /my-documents/{id}/publish-sites` (`receipts.publishAdditionalSites`) | `UatPerSiteEffectiveIT.themSiteBVoiNgayHieuLucRieng` |
| UAT-21 `[BẢO MẬT]` | Reader (site B) | Site B chưa tới ngày hiệu lực của B | **When** Reader site B mở danh mục/`/documents/{id}`; **Then** không thấy bản trước ngày hiệu lực của site B | Bản chưa áp dụng cho site B bị ẩn/404 theo scope | `GET /documents` + `GET /documents/{id}` (`CatalogService` scope + per-site) | `UatPerSiteEffectiveIT.readerSiteBKhongThayTruocNgayHieuLuc` |

---

## D. DC Site chấp nhận / từ chối phiếu (apply_date = ngày hiệu lực site)

| Mã ca | Vai trò | Tiền điều kiện | Các bước (Given/When/Then) | Kết quả mong đợi | Endpoint/Controller | Test tự động |
|---|---|---|---|---|---|---|
| UAT-30 | DC Site | Có phiếu `cho_phan_hoi` cho site mình, ngày site = hôm nay | **When** POST `/site-receipts/{id}/accept`; **Then** phiếu sang `chap_nhan`, `apply_date` = ngày hiệu lực đã khai cho site | Receipt `chap_nhan`; `apply_date` = site_effective_date | `POST /site-receipts/{id}/accept` (`receipts.acceptReceipt`) | `UatReceiptFlowIT.dcSiteChapNhanApplyDateBangNgaySite` |
| UAT-31 `[BẢO MẬT]` | DC Site | Phiếu có ngày hiệu lực site ở **tương lai** | **When** POST accept trước ngày; **Then** bị chặn | HTTP 4xx, mã `receipt.before_effective` | `POST /site-receipts/{id}/accept` | `UatReceiptFlowIT.chapNhanTruocNgaySiteBiChan` |
| UAT-32 `[BẢO MẬT]` | DC Site site khác | Phiếu thuộc site khác | **When** mở `/site-receipts/{id}` của site không thuộc quyền; **Then** bị chặn 403 | HTTP 403 | `GET /site-receipts/{id}` (`permissions.siteIdsWithDocControl`) | `UatReceiptFlowIT.dcSiteKhacMoPhieuBi403` |
| UAT-33 | DC Site | Phiếu `cho_phan_hoi` | **When** POST `/site-receipts/{id}/reject` loại `sai_dang_ky` + lý do; **Then** phiếu sang `tu_choi`; Author thấy ở trang chủ | Receipt `tu_choi`; hiện trong "việc cần làm" của Author | `POST /site-receipts/{id}/reject` + `GET /` (`HomeController`) | `UatReceiptFlowIT.tuChoiSaiDangKyHienOHomeAuthor` |
| UAT-34 `[BẢO MẬT]` | DC Site | — | **When** POST reject loại không hợp lệ; **Then** HTTP 400 | HTTP 400 "Loại từ chối không hợp lệ" | `POST /site-receipts/{id}/reject` | `UatReceiptFlowIT.tuChoiLoaiKhongHopLe400` |

---

## E. Reader xem danh mục theo scope, xác nhận đọc, báo sai nội dung

| Mã ca | Vai trò | Tiền điều kiện | Các bước (Given/When/Then) | Kết quả mong đợi | Endpoint/Controller | Test tự động |
|---|---|---|---|---|---|---|
| UAT-40 | Reader | Tài liệu hiệu lực trong phạm vi + Reader thuộc ma trận phân phối (`require_ack`) | **When** Reader mở `/documents/{id}` rồi POST `/documents/{id}/acknowledge`; **Then** ghi nhận đã đọc, lưu bối cảnh dept lúc xác nhận | Ack được ghi; nút chuyển "đã xác nhận" | `GET`/`POST /documents/{id}/acknowledge` (`AcknowledgementService`) | `UatReaderAckIT.readerXacNhanDaDoc` |
| UAT-41 `[BẢO MẬT]` | Reader ngoài diện | Reader không thuộc ma trận phân phối | **When** POST `/documents/{id}/acknowledge`; **Then** bị chặn | HTTP 4xx "Bạn không thuộc diện phải xác nhận đọc" | `POST /documents/{id}/acknowledge` | `UatReaderAckIT.readerNgoaiDienKhongXacNhanDuoc` |
| UAT-42 (IND-04) | Reader → DC Site | Phiếu site đã `chap_nhan` | **When** Reader POST `/documents/{id}/report` sai nội dung; DC Site POST `/issue-reports/{id}/convert` loại `sai_noi_dung`; **Then** receipt GIỮ `chap_nhan`, report sang `bao_sai_noi_dung_sau_chap_nhan`; Author thấy ở trang chủ | Receipt vẫn `chap_nhan`; report post-accept; Author recall được dù 100% chấp nhận | `POST /documents/{id}/report` + `POST /issue-reports/{id}/convert` (`IssueReportService`) | `UatReaderAckIT.baoSaiNoiDungSauChapNhanIND04` |

---

## F. Thu hồi tài liệu (IND-01) + supersede

| Mã ca | Vai trò | Tiền điều kiện | Các bước (Given/When/Then) | Kết quả mong đợi | Endpoint/Controller | Test tự động |
|---|---|---|---|---|---|---|
| UAT-50 (IND-01) | Author | v1.0 có phiếu đang chờ cho site A | **When** Author đăng ký v2.0 supersede v1.0; **Then** v1.0 sang `het_hieu_luc`, phiếu v1 biến khỏi hàng chờ, chấp nhận phiếu v1 bị chặn | v1 `het_hieu_luc`; phiếu cũ loại khỏi `/site-receipts`; accept → `receipt.superseded` | `POST /register` (supersede) + `GET /site-receipts` + accept | `UatRecallIT.supersedeLoaiPhieuCuIND01` |
| UAT-51 | Author | Bản 100% site đã chấp nhận (có report sai nội dung) | **When** Author POST `/my-documents/{id}/recall` kèm lý do; **Then** bản sang `thu_hoi` | Bản `thu_hoi`; QR của bản in báo vô hiệu | `POST /my-documents/{id}/recall` (`receipts.recallByAuthor`) | `UatRecallIT.recallDuDaChapNhan` (phần recall của IND-04) + `UatReaderAckIT` |

---

## G. In bản kiểm soát + tra cứu QR công khai (IND-05)

| Mã ca | Vai trò | Tiền điều kiện | Các bước (Given/When/Then) | Kết quả mong đợi | Endpoint/Controller | Test tự động |
|---|---|---|---|---|---|---|
| UAT-60 | Author/DC Site | Bản hiệu lực | **When** POST `/documents/{id}/print`; **Then** sinh bản in có `verify_token` ngẫu nhiên + URL tra cứu | Trang kết quả in; token tra cứu được | `POST /documents/{id}/print` (`PrintingService.registerPrint`) | `UatPrintVerifyIT.inSinhTokenVaTraCuuCongKhai` |
| UAT-61 `[BẢO MẬT]` | Công khai (không đăng nhập) | Có token của bản in | **When** mở `/verify/{token}` KHÔNG đăng nhập; **Then** trả trang tình trạng, không nội dung | HTTP 200, hiển thị tình trạng (CÒN/HẾT hiệu lực), không lộ nội dung | `GET /verify/{token}` (`PrintingController` — route công khai duy nhất) | `UatPrintVerifyIT.inSinhTokenVaTraCuuCongKhai`, `UatPrintVerifyIT.tokenSaiBaoKhongTimThay` |
| UAT-62 `[BẢO MẬT]` | Công khai | Token sai | **When** mở `/verify/<token-sai>`; **Then** báo không tìm thấy bản in | HTTP 200, "Không tìm thấy bản in" | `GET /verify/{token}` | `UatPrintVerifyIT.tokenSaiBaoKhongTimThay` |

---

## H. Tìm kiếm full-text theo scope + MCP

| Mã ca | Vai trò | Tiền điều kiện | Các bước (Given/When/Then) | Kết quả mong đợi | Endpoint/Controller | Test tự động |
|---|---|---|---|---|---|---|
| UAT-70 | Author | Có tài liệu trong phạm vi | **When** GET `/search?q=...`; **Then** trả kết quả trong phạm vi, ghi audit `tim_kiem` | HTTP 200; tài liệu của mình xuất hiện | `GET /search` (`SearchService`) | `UatSearchMcpIT.timKiemTheoScope` |
| UAT-71 `[BẢO MẬT]` | 2 vai khác phạm vi | — | **When** MCP `get_document_markdown` cho doc ngoài phạm vi vs doc không tồn tại; **Then** hai nhánh trả thông điệp notFound GIỐNG HỆT | `isError`; text = `McpServer.NOT_FOUND_MESSAGE` cho cả hai | `POST /mcp` (`McpController` → `McpServer`) | `UatSearchMcpIT.mcpMarkdownNgoaiPhamViGiongKhongTonTai` |
| UAT-72 | Author | Phiên hợp lệ | **When** POST `/mcp` `tools/call` `search_documents` trong phạm vi; **Then** trả tài liệu của mình | Kết quả chứa doc_id vừa tạo | `POST /mcp` (`search_documents`) | `UatSearchMcpIT.mcpSearchTrongPhamVi` |
| UAT-73 `[BẢO MẬT]` | — | Không cookie | **When** POST `/mcp` không cookie; **Then** 401 | HTTP 401 | `POST /mcp` | `UatSearchMcpIT.mcpKhongCookie401` |

---

## I. R010 — phân quyền phạm vi nhà máy (site scope)

| Mã ca | Vai trò | Tiền điều kiện | Các bước (Given/When/Then) | Kết quả mong đợi | Endpoint/Controller | Test tự động |
|---|---|---|---|---|---|---|
| UAT-80 `[BẢO MẬT]` | DC Site MML vs HCM | Phiếu thuộc site HCM | **When** DC Site MML cố phản hồi phiếu site HCM; **Then** bị chặn | `permissions.canRespondReceipt` false cho site khác; HTTP 403 ở `/site-receipts/{id}` | `ReceiptController` + `PermissionService` | `UatReceiptFlowIT.dcSiteKhacMoPhieuBi403` (phủ R010 ở tầng web) |

---

## Ma trận truy vết UAT → test

| Nhóm | Mã ca | File test |
|---|---|---|
| A. Đăng nhập/phân quyền | UAT-01..07 | `UatAuthFlowIT` |
| B. Đăng ký ban hành | UAT-10, 11 | `UatAuthFlowIT` |
| C. Per-site effective | UAT-20, 21 | `UatPerSiteEffectiveIT` |
| D. Chấp nhận/từ chối phiếu | UAT-30..34, 80 | `UatReceiptFlowIT` |
| E. Reader đọc/ack/báo sai | UAT-40..42 | `UatReaderAckIT` |
| F. Thu hồi/supersede | UAT-50, 51 | `UatRecallIT` (+ `UatReaderAckIT` cho IND-04c) |
| G. In + QR công khai | UAT-60..62 | `UatPrintVerifyIT` |
| H. Tìm kiếm + MCP | UAT-70..73 | `UatSearchMcpIT` |

> **Ghi chú phạm vi test tự động:** UAT-12 (thiếu tệp) được phủ ở tầng đơn vị
> đăng ký, không lặp lại ở test HTTP. Các kiểm tra giao diện (cuộn ngang, màu +
> ký hiệu, zoom, in đen trắng) vẫn là kiểm thủ công — không tự động hoá trong
> phạm vi này.
