# DCS-5 — Business rules (IF–THEN, trích từ code)

> Mỗi rule suy từ code xử lý thật, kèm vị trí `file:dòng`. Cuối mục là bảng đối chiếu **spec cũ → code mới**.

## BR-01 — Ban hành thẳng khi đăng ký
**IF** Author có `DC_AUTHOR` nộp `/register` hợp lệ và chọn ≥1 site nhận
**THEN** tạo `document_versions.status = 'da_ban_hanh'` ngay (không qua ai duyệt) **AND** sinh `document_receipt='cho_phan_hoi'` cho mỗi site **AND** audit `nop_dang_ky` + `ban_hanh`.
→ `registration.ts:submitRegistration:154-262`; `reg.no_site` nếu không chọn site (`:216-219`).

## BR-02 — Supersede giữ liên kết phiên bản & ràng buộc 1 bản hiện hành (điểm 1)
**IF** đăng ký bản thay thế (`supersedes_version_id` trỏ bản `da_ban_hanh/hieu_luc/da_dang_ky`)
**THEN** chuyển bản gốc → `het_hieu_luc` TRƯỚC khi chèn bản mới (giữ liên kết qua `supersedes_version_id`), để không vi phạm `uq_one_current_per_doc` (chỉ 1 bản `da_ban_hanh`/`doc_id`).
→ `registration.ts:197-224`; ràng buộc `026_lifecycle_sync.sql:~95`; `validateForSubmit` chặn supersede sai trạng thái (`registration.ts:131-146`, `reg.supersedes`).

## BR-03 — Kế thừa xác nhận đọc theo mức độ thay đổi
**IF** bản mới `change_significance = 'nho'` và có `supersedes_version_id`
**THEN** SAO CHÉP `read_acknowledgements` của bản gốc sang bản mới (`inherited_from_version_id` giữ gốc) — cả xưởng không phải xác nhận lại.
**IF** `change_significance = 'lon'` **THEN** KHÔNG kế thừa — mọi người xác nhận lại.
→ đăng ký mới: `registration.ts:244-253`; chuyển hiệu lực theo lịch (dữ liệu cũ): `fn_activate_due_versions` (`026_lifecycle_sync.sql:95-104`). Khai `nho` phải giải trình `change_summary` khi `lon` (`registration.ts:140-146`).

## BR-04 — Chuyển hiệu lực theo ngày (dữ liệu cũ / gói)
**IF** version `da_dang_ky` (không thuộc gói) có `effective_date <= CURRENT_DATE`
**THEN** bản cũ `hieu_luc` → `het_hieu_luc`, bản này → `hieu_luc` trong CÙNG giao dịch (giữ `uq_one_effective_per_doc`); đóng rà soát `treo_cho_ban_moi`; kế thừa ack nếu `nho`; audit `chuyen_hieu_luc`.
**IF** là gói `dang_mo` tới hạn và MỌI thành viên `da_dang_ky` **THEN** kích hoạt nguyên tử cả gói → `da_kich_hoat` (thiếu 1 thành viên thì cả gói dừng).
→ `fn_activate_due_versions` (`026_lifecycle_sync.sql:48-180`), gọi qua `lifecycle.ts:activateDueVersions:11-14` trong scheduler.

## BR-05 — Sinh rà soát định kỳ (điểm 2)
**IF** version `status ∈ {da_ban_hanh, hieu_luc}` có `next_review_date <= CURRENT_DATE` và chưa có bản ghi rà soát đang mở
**THEN** INSERT `periodic_reviews` (`dang_ra_soat`) + audit `sinh_ra_soat`.
→ `fn_generate_due_reviews` (`026_lifecycle_sync.sql:22-47` — **đã mở rộng quét `da_ban_hanh`**, trước chỉ `hieu_luc`), gọi qua `lifecycle.ts:generateDueReviews:17-20`.
`next_review_date` đặt khi INSERT bản `da_ban_hanh` (trigger `fn_set_next_review`, `001_schema.sql`). Không có rule này thì preset "Quá hạn xem xét" + theo dõi của DC Site luôn rỗng.

## BR-06 — Kết luận rà soát đặt lại đồng hồ
**IF** bộ phận sở hữu ghi kết luận:
- `giu_nguyen` → `da_dong` **AND** `next_review_date = CURRENT_DATE + review_cycle_months` (đường DUY NHẤT sửa trường này sau khi hiệu lực).
- `can_sua` → `treo_cho_ban_moi` (tự đóng khi bản mới lên hiệu lực — BR-04).
- `huy_bo` → `huy_bo`.
→ `fn_record_review_verdict` (`001_schema.sql`); quyền: chỉ người thuộc `owner_department_id` (`review.ts:recordVerdict:82-108`, `review.no_permission`).

## BR-07 — Hết hạn CHỈ cảnh báo, không tự đổi trạng thái (điểm 9)
**IF** version hiện hành quá `expiry_date`
**THEN** chỉ hiển thị cảnh báo: banner "ĐÃ QUÁ NGÀY HẾT HẠN" ở detail + preset danh mục `expired`. KHÔNG tự chuyển `het_hieu_luc`.
→ Khối tự-hết-hạn ĐÃ GỠ khỏi `fn_activate_due_versions` (`026_lifecycle_sync.sql:155-157`); cảnh báo ở `catalog.ts domain:132` (`qua_han_su_dung`) + `detail.eta` banner + preset `expired` (`catalog.ts domain:133`).

## BR-08 — Chặn hiển thị/chấp nhận khi chưa tới ngày hiệu lực (điểm 4)
**IF** Reader xem danh mục/chi tiết **THEN** chỉ thấy version hiện hành VÀ `effective_date <= CURRENT_DATE` (`listVisibleDocuments`/`canViewVersion` readOnly: `catalog.ts domain:116-117`, `:186-187`; `isEffectiveNow:51-59`).
**IF** DC Site bấm chấp nhận mà `effective_date > hôm nay` **THEN** chặn `receipt.before_effective` (`receipt.ts:381-383`).

## BR-09 — Khóa metadata khi đã có site chấp nhận (điểm 6, dùng chung cho sửa & phát hành lại)
**IF** version có ≥1 receipt `chap_nhan`
**THEN** KHÔNG sửa metadata tại chỗ, KHÔNG phát hành lại với metadata cũ — phải ra phiên bản mới qua thu hồi. Một mã lỗi thống nhất `issue.locked_accepted`.
→ `assertNotAcceptedLocked` (`receipt.ts:460-463`) gọi trong `editVersionMetadata` (`:489`) và `publishAdditionalSites` (`:629`); UI khóa ở `edit-metadata.eta` (`locked`) và `issuance-status.eta`.

## BR-10 — Sửa metadata không mất ngày hết hạn + chặn ngày hiệu lực quá khứ (điểm 10)
**IF** sửa metadata không gửi lại `expiry_date` (`undefined`) **THEN** GIỮ NGUYÊN giá trị cũ; chỉ xóa khi gửi sentinel rỗng `''` (→ NULL).
**IF** gửi `effective_date` < hôm nay **THEN** chặn `edit.effective_past`.
→ route phân biệt "không gửi" vs "gửi rỗng" (`documents.ts:~330-345`); domain `editVersionMetadata` (`receipt.ts:493-513`).

## BR-11 — Thu hồi (Author) & vô hiệu phiếu sau thu hồi (điểm 5)
**IF** là người đăng ký, status `da_ban_hanh`, có lý do
**THEN** → `thu_hoi` (MỞ: cho thu hồi kể cả khi mọi site đã chấp nhận); audit `thu_hoi` (tally + affected_site_ids).
**AND** phiếu `cho_phan_hoi` của tài liệu đã thu hồi KHÔNG chấp nhận được (`receipt.recalled`) **VÀ** loại khỏi hàng chờ + badge menu (JOIN `version.status <> 'thu_hoi'`).
→ `recallByAuthor` (`receipt.ts:565-607`); chặn chấp nhận `assertCanRespond` (`:376-379`); loại khỏi đếm `pendingReceiptCountForSites`/`pendingReceiptsForSites` (`:252-279`).

## BR-12 — Report của Reader xử lý đồng nhất với receipt (điểm 7)
**IF** DC Site chuyển report thành từ chối mà receipt đang `cho_phan_hoi` **THEN** receipt → `tu_choi` + report `chuyen_phan_hoi` (nguyên tử).
**IF** receipt đã `chap_nhan` và loại `sai_dang_ky` **THEN** CHẶN (`report.already_accepted` — phải thu hồi + bản mới).
**IF** receipt đã `chap_nhan` và loại `sai_noi_dung` **THEN** report → `bao_sai_noi_dung_sau_chap_nhan`, receipt GIỮ `chap_nhan`, audit riêng (không còn lệch dữ liệu).
→ `convertReportToRejection` (`issue-report.ts:130-194`); enum + event mới ở `027_report_content_error_after_accept.sql`.

## BR-13 — apply_date không sớm hơn ngày hiệu lực (điểm 8)
**IF** DC Site chấp nhận với `apply_date < effective_date`
**THEN** chặn `receipt.apply_before_effective`.
→ `acceptReceipt` (`receipt.ts:409-412`); ràng buộc DB `chk_receipt_apply_date` (chấp nhận phải có apply_date, `017_receipt_model.sql`).

## BR-14 — In bản sao kiểm soát (mở cho trạng thái ban hành mới — điểm 3)
**IF** version hiện hành (`isCurrentlyEffective` = `da_ban_hanh/hieu_luc/da_dang_ky`) và người in có quyền xem
**THEN** sinh `printed_copies` với `verify_token` NGẪU NHIÊN (DB default `gen_random_bytes`), overlay khi kết xuất, QR SVG; audit `in`.
**ELSE** chặn `print.not_effective`.
→ `registerPrint` (`printing.ts:33-67`); dùng `isCurrentlyEffective` (`:14,:44`) — mở cho `da_ban_hanh`, không chỉ `hieu_luc` cũ.

## BR-15 — Tra cứu QR chỉ trả tình trạng
**IF** quét `/verify/:token` **THEN** trả tình trạng (`con_hieu_luc/het_hieu_luc/thu_hoi/ban_in_thu_hoi/khong_ro`); kèm `?site_id=` → tình trạng tiếp nhận theo site. TUYỆT ĐỐI không trả nội dung/nhãn/danh sách người nhận. Không yêu cầu đăng nhập.
→ `verifyToken` (`printing.ts:86-158`); bản hiện hành dùng `da_ban_hanh/hieu_luc` (`:115-118`).

## BR-16 — Thu hồi bản in tại hiện trường
**IF** người có quyền xem bản bấm thu hồi một `printed_copies` `dang_luu_hanh`
**THEN** → `da_thu_hoi` (idempotent), audit `thu_hoi_ban_in`.
→ `recallPrintedCopy` (`printing.ts:171-193`); event thêm ở `022_audit_event_thu_hoi_ban_in.sql`.

## BR-17 — Xác nhận đọc lưu bối cảnh tại thời điểm xác nhận
**IF** người thuộc diện phải đọc (`mustAcknowledge` qua `v_distribution_resolved`, phép GIAO bộ phận × nhóm) bấm "Xác nhận đã đọc"
**THEN** INSERT `read_acknowledgements` kèm `department_id`/`functional_group_id` TẠI THỜI ĐIỂM xác nhận (không tra lại L1 sau), audit `xac_nhan_doc`.
→ `acknowledge` (`acknowledgement.ts:46-72`). Đổi bộ phận/rời nhóm sau đó không làm sai lệch bằng chứng.

## BR-18 — Dọn phiên hết hạn
**IF** tác vụ nền chạy **THEN** `purgeExpiredSessions` xóa `app_session` quá `expires_at` (TTL 8 giờ).
→ `scheduler.ts:25`; phiên tạo ở `003_auth_giai_doan_1.sql:26`.

## BR-19 — Leo thang tồn đọng (chỉ thông báo)
**IF** tồn đọng (`cho_xac_nhan`/`ra_soat_qua_han`/`goi_treo`) vượt ngưỡng theo bậc và bậc đó chưa gửi
**THEN** gửi đúng MỘT thông báo tới đích (`nguoi_giu_viec/quan_ly_bo_phan/dcs_admin/cap_tren`), audit `leo_thang`. KHÔNG đổi trạng thái gì (`uq_escalation` chống gửi lại).
→ `fn_run_escalations` (`005_ver2_organization_authorization.sql`), gọi `escalation.ts:runEscalations:12-15` trong scheduler; nguồn tồn đọng `v_backlog_all`.

## BR-20 — Gói ban hành (ràng buộc)
- Tạo gói: chỉ `DC_AUTHOR` (`pkg.no_permission`), không Admin (`package.ts:createPackage:46-65`).
- Gắn thành viên: là người đăng ký, status `cho_xac_nhan/da_dang_ky`, gói đang mở, ≤10 thành viên (trigger `fn_validate_package_member`); set `effective_date=NULL`, `package_id` (`package.ts:72-104`).
- Dời ngày: chỉ người tạo gói; **chỉ dời RA XA** khi đã có thành viên xác nhận (trigger `fn_guard_package_date`) (`package.ts:107-124`).
- Tháo thành viên: bắt buộc lý do; trả lại `effective_date` = ngày gói (`package.ts:130-150`).

## BR-21 — Mẫu chuẩn (FR-05) — CHƯA CÓ CODE
Spec `reference-sample-management` yêu cầu: tạo yêu cầu lấy mẫu mới, trạng thái "Cần thay mẫu", lead time, ngày cảnh báo, đường quay "Cần thay mẫu"→"Có hiệu lực". **Không có bảng/route/domain nào hiện thực** (grep `reference_sample|can_thay_mau|lay_mau|mau_chuan` trong `src/` = 0 kết quả). Mâu thuẫn FR-05/FR-06 là **vấn đề trên giấy, chưa có code** — xem DCS-7 §Điểm mở điểm 11.

---

## Bảng đối chiếu spec cũ → code mới

Chú thích: ✅ khớp · ⚠️ lệch (spec cũ còn mô tả mô hình cũ) · ❌ spec có, code chưa có · ➕ code có, spec chưa ghi.

| Chủ đề | Spec cũ mô tả | Code mới | Trạng thái |
|--------|---------------|----------|-----------|
| Mô hình ban hành | Duyệt 2 lớp (Dept Head gác cổng trước ban hành) | Đăng ký = ban hành thẳng `da_ban_hanh` | ⚠️ spec cũ lạc hậu (`017`, `lifecycle-status.ts`) |
| Vai trò nguồn | DC_DEPT_HEAD / Site QA / BU / Group owner gác cổng | Gỡ; chỉ Admin/Author/DC_SITE_DOC_CONTROL/Reader | ⚠️ (`018`, `021`, `permissions.ts`) |
| Phản hồi sau ban hành | — (ack đọc là chính) | Receipt theo site: chấp nhận/từ chối (sai đăng ký/sai nội dung) | ➕ (`017`, `receipt.ts`) |
| Report bất thường của Reader | — | `document_issue_report` + thẩm định DC Site | ➕ (`017`, `issue-report.ts`) |
| Số migration | tài liệu cũ ghi 16 (rồi 25) | **27** migration thật (gồm 026/027) | ⚠️ sửa số liệu (DCS-7) |
| Rà soát định kỳ | quét `hieu_luc` | quét `da_ban_hanh` + `hieu_luc` | ✅ đã vá (`026`) |
| In + ack | chỉ `hieu_luc` | mở cho `da_ban_hanh` | ✅ đã vá (`printing.ts`, `catalog.ts`) |
| Hết hạn | job tự chuyển `het_hieu_luc` | chỉ cảnh báo, không đổi trạng thái | ✅ đã vá (`026`) |
| supersede chọn bản gốc | chỉ nhận trạng thái cũ | nhận `da_ban_hanh` + ràng buộc 1 bản hiện hành | ✅ đã vá (`registration.ts`, `026`) |
| Thu hồi rồi site vẫn phản hồi | cho phản hồi | vô hiệu phiếu + loại khỏi badge | ✅ đã vá (`receipt.ts`) |
| Khóa metadata sau chấp nhận | không thống nhất | 1 guard chung sửa & phát hành lại | ✅ đã vá (`receipt.ts`) |
| Report → từ chối khi đã chấp nhận | lệch dữ liệu (report đóng, receipt vẫn chấp nhận) | chặn sai đăng ký; đường báo sai nội dung riêng | ✅ đã vá (`027`, `issue-report.ts`) |
| apply_date vs effective | không chặn | chặn apply_date < effective | ✅ đã vá (`receipt.ts`) |
| Sửa metadata xóa expiry | ghi NULL khi không nhập | giữ nguyên, chỉ xóa khi sentinel rỗng | ✅ đã vá (`receipt.ts`) |
| Mẫu chuẩn FR-05 | yêu cầu "Cần thay mẫu", lead time… | chưa có bảng/route/domain | ❌ (`reference-sample-management` spec, điểm 11) |
| Sửa/thu hồi tài liệu chưa xác nhận | spec mới có khung | khung spec `author-edit-recall-unacknowledged-docs` | ⚠️/❌ phần khung — xem Điểm mở |
