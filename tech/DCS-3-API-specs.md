# DCS-3 — API specs

> Trích từ code route thật (`src/routes/*`). Đa số endpoint là SSR (trả HTML `.eta`) hoặc form-post rồi redirect; chỉ `/healthz`, `/mcp`, và các endpoint xuất file khác dạng.

## 0. Quy ước chung

- **Base path:** cấu hình qua `BASE_PATH` (vd `/dcs`); cookie phiên gắn theo path đó (`config.ts:106-110`, `middleware/auth.ts:19-23`).
- **Xác thực:** mọi route nghiệp vụ gọi `requireUser` (chưa đăng nhập → 302 `/login`) hoặc `requireDocFlow` (chặn Admin-thuần → 403) (`middleware/auth.ts:67-74`, `:81-94`).
- **Lỗi nghiệp vụ:** `BusinessError` (mã + `httpStatus`, mặc định **422**) (`lib/errors.ts:13-31`). Route bắt và gọi `sendBusinessError`: htmx → fragment toast + giữ status; thường → trang `error.eta` (`lib/http-errors.ts:33-52`).
- **Thành công (htmx):** `sendOk` trả `204` + header `HX-Redirect` (`lib/http-errors.ts:58-70`).
- **Lỗi ràng buộc DB (lớp 23)** KHÔNG được nuốt — là dấu hiệu thiếu kiểm tra ở domain (`lib/errors.ts:40-52`). Tầng domain kiểm tra TRƯỚC.

## 1. Auth (`src/routes/auth.ts`)

| Method | Path | Quyền | Request | Response | Lỗi |
|--------|------|-------|---------|----------|-----|
| GET | `/login` | công khai; đã đăng nhập → 302 `/` | — | `login.eta` | — |
| POST | `/login` | công khai | form `email`, `password` | 302 `/` + set cookie `dcs_sid` (ký, httpOnly, sameSite lax, 8h) | `BusinessError` → `login.eta` kèm `error` |
| GET | `/logout` | — | — | 302 `/login` + huỷ phiên | — |

Nguồn: `auth.ts:13-43`; cookie `setSessionCookie` (`middleware/auth.ts:97-106`).

## 2. Trang chủ & health (`src/routes/home.ts`)

| Method | Path | Quyền | Response |
|--------|------|-------|----------|
| GET | `/` | đăng nhập | `home.eta`: DC Site thấy receipt chờ phản hồi; Author thấy tài liệu bị site báo sai sót (receipt `tu_choi`) |
| GET | `/healthz` | công khai | JSON `{status:'ok', phase:'receipt'}` |

Nguồn: `home.ts:18-43`.

## 3. Danh mục & chi tiết (`src/routes/catalog.ts`)

| Method | Path | Quyền | Request | Response | Ghi chú |
|--------|------|-------|---------|----------|---------|
| GET | `/documents` | `requireDocFlow` | querystring: `status, q, doc_type, origin, eff_from, eff_to, preset, sort, site_id, rollout_status` | `catalog.eta` | Reader chỉ thấy bản hiện hành + đã tới ngày hiệu lực (`listVisibleDocuments` readOnly) |
| GET | `/documents/export.xlsx` | `requireDocFlow` + `canExportCatalog` (Author hoặc DC Site) | cùng querystring lọc | file `.xlsx` (11 cột, không có cột PDF) | 403 nếu không đủ quyền |
| GET | `/documents/:id/preview` | `requireUser` + người đăng ký (bản nháp/chưa phát hành) hoặc `canViewVersion` | — | PDF inline | 404 tệp không còn |
| GET | `/documents/:id/download` | `requireUser` + `isCurrentStatus` + `canViewVersion` | — | PDF attachment | 403 nếu không hiện hành |
| GET | `/documents/:id/view` | như download | — | PDF inline | — |
| GET | `/documents/:id` | `requireDocFlow` | — | `detail.eta` (sections, links, receipts, ackState, printedCopies) | Reader mở bản không hiện hành → 302 về bản đang áp dụng; không có → 404 |
| POST | `/documents/:id/acknowledge` | `requireUser` | — | 302 `/documents/:id` | `ack.not_required` nếu không thuộc diện phải đọc |
| POST | `/documents/:id/process` | `requireUser` + người đăng ký | — | 302 `/documents/:id` (bóc tách nội dung) | 403 |

Nguồn: `catalog.ts:80-284`. Bộ lọc danh mục & preset xem DCS-4/DCS-5.

## 4. Đăng ký & tài liệu của tôi (`src/routes/documents.ts`)

| Method | Path | Quyền | Request (form multipart) | Response | Lỗi nghiệp vụ |
|--------|------|-------|--------------------------|----------|---------------|
| GET | `/register` | `requireUser` + `canRegisterDocuments` | — | `register.eta` (bus/sites/depts/docTypes/supersedeOptions) | 403 |
| POST | `/register` | như trên | `doc_id, doc_type, origin, scope_level, scope_bu_id, scope_site_id, scope_dept_id, version_no, title, issued_date, external_effective_date, effective_date, review_cycle_months, expiry_date, owner_department_id, supersedes_version_id, change_significance, change_summary, site_ids[], file (PDF)` | 302 `/my-documents` | `reg.file, reg.no_site, reg.scope_*, reg.duplicate, reg.effective_past, reg.expiry, reg.supersedes, reg.change_sig, reg.change_summary, reg.ext_date, reg.no_permission(403)` |
| POST | `/register/draft` | như trên | như trên (không validate đủ) | 302 `/my-documents` | `reg.no_permission(403)` |
| GET | `/register/recipient-count` | `requireUser` | query `owner_department_id, fg` | HTML fragment `<span>Số người sẽ nhận…</span>` | — |
| GET | `/my-documents` | `requireUser` + `canRegisterDocuments` | — | `my-documents.eta` (lọc `registrant_user_id`) | 403 |
| GET | `/my-documents/export.xlsx` | như trên | — | file `.xlsx` (7 cột) | 403 |
| GET | `/my-documents/:id/issuance` | `requireUser` + người đăng ký | — | `issuance-status.eta` (ma trận receipt + hành động) | 403/404 |
| GET/POST | `/my-documents/:id/edit-metadata` | `requireUser` + người đăng ký | form: `title, issued_date, effective_date, review_cycle_months, expiry_date, owner_department_id` | GET `edit-metadata.eta`; POST → 302 issuance | `issue.locked_accepted, edit.bad_status, edit.effective_past` |
| GET/POST | `/my-documents/:id/recall` | `requireUser` + người đăng ký | form `reason` | GET `author-recall.eta`; POST → 302 `/my-documents` | `recall.reason, recall.bad_status` |
| POST | `/my-documents/:id/publish-sites` | `requireUser` + người đăng ký | form `site_ids[]` + **`eff_<site_id>` (date, mỗi site một field, tùy chọn)** | 302 issuance | `publish.bad_status, publish.content_reject, publish.out_of_scope, publish.nothing, issue.locked_accepted, publish.effective_past, publish.effective_before_issued` |

> **Luồng "thêm site ngày khác" (D3):** route gom các field `eff_<site_id>` thành `Record<site_id, 'YYYY-MM-DD'>` (bỏ rỗng) rồi truyền `siteEffectiveDates` vào `publishAdditionalSites`. Site không nhập ngày → `NULL` ⇒ coalesce về ngày hiệu lực gốc. KHÔNG mở lại `/register`. Nguồn: `documents.ts` handler `publish-sites`. [mới kiểm chứng đọc code]
| POST | `/my-documents/:id/delete` | `requireUser` + người đăng ký | — | 302 `/my-documents` | `del.locked` |
| GET/POST | `/my-documents/:id/edit` | `requireUser` + người đăng ký + status `cho_hoan_thien` | form đăng ký | GET `register.eta` (điền sẵn); POST → 302 `/my-documents` | 404 |

Request/response schema chi tiết (field) xem DCS-4. Logic domain xem DCS-5.
Nguồn: `documents.ts:143-578`.

## 5. Phản hồi receipt — DC Site (`src/routes/receipts.ts`)

| Method | Path | Quyền | Request | Response | Lỗi |
|--------|------|-------|---------|----------|-----|
| GET | `/site-receipts` | `requireUser`; DC Site | — | `site-receipts.eta` (hàng chờ + tài liệu bị thu hồi); `notControl=true` nếu không giữ site nào | — |
| GET | `/site-receipts/:id` | `requireUser` + DC Site đúng site | — | `receipt-respond.eta` (detail + sections + cờ selfRegistered) | 403/404 |
| POST | `/site-receipts/:id/accept` | như trên | — (**IND-03: KHÔNG nhận `apply_date`**) | `sendOk` → `/site-receipts` | `receipt.bad_status, receipt.recalled, receipt.superseded, receipt.before_effective, receipt.no_effective, receipt.no_permission(403)` (HƯỚNG 2: `apply_before_effective` đã gỡ — apply_date = site_effective_date độc lập) |
| POST | `/site-receipts/:id/reject` | như trên | form `reject_type∈{sai_dang_ky,sai_noi_dung}`, `note` (bắt buộc) | `sendOk` → `/site-receipts` | 400 loại không hợp lệ; `receipt.reason`, các lỗi như trên |

Nguồn: `receipts.ts`; `receipt.ts:acceptReceipt`.

> **IND-03 (D2) — `/accept` KHÔNG nhận `apply_date`:** route gọi `acceptReceipt(sql, { receiptId, userId })` — không có tham số ngày. "Chấp nhận" = đồng ý với ngày đã khai; domain tự đặt `apply_date = coalesce(site_effective_date, effective_date)`, không phải ngày bấm. Nếu không có ngày hiệu lực nào → `receipt.no_effective`. [mới kiểm chứng đọc code]

## 6. Report bất thường (`src/routes/issue-reports.ts`)

| Method | Path | Quyền | Request | Response | Lỗi |
|--------|------|-------|---------|----------|-----|
| POST | `/documents/:id/report` | `requireUser` (mọi vai đọc trừ Admin-thuần) | form `note` (bắt buộc) | `sendOk` → `/documents/:id` | `report.note, report.no_site, report.not_found` |
| GET | `/issue-reports` | `requireUser`; DC Site | — | `issue-reports.eta` (hộp report `moi` của site); `notControl` nếu không giữ site | — |
| POST | `/issue-reports/:id/convert` | DC Site đúng site | form `reject_type`, `note` | `sendOk` → `/issue-reports` | 400 loại sai; `report.bad_status, report.no_receipt, report.already_accepted, report.receipt_not_pending, report.no_permission(403)` |
| POST | `/issue-reports/:id/dismiss` | DC Site đúng site | form `review_note` (bắt buộc) | `sendOk` → `/issue-reports` | `report.dismiss_note, report.bad_status` |

Nguồn: `issue-reports.ts:21-90`.

## 7. Rà soát định kỳ (`src/routes/reviews.ts`)

| Method | Path | Quyền | Request | Response | Lỗi |
|--------|------|-------|---------|----------|-----|
| GET | `/reviews` | `requireDocFlow` | — | `reviews.eta`: Author thấy tồn đọng bộ phận sở hữu (có nút kết luận) + treo chờ bản mới; DC Site thấy bảng nhắc chỉ-đọc theo site | — |
| POST | `/reviews/:id/verdict` | `requireDocFlow` + thuộc bộ phận sở hữu | form `verdict∈{giu_nguyen,can_sua,huy_bo}`, `note` | 302 `/reviews` | `review.note, review.not_found, review.no_permission(403)` |

Nguồn: `reviews.ts:16-50`.

## 8. In & tra cứu QR (`src/routes/printing.ts`)

| Method | Path | Quyền | Request | Response | Lỗi |
|--------|------|-------|---------|----------|-----|
| POST | `/documents/:id/print` | `requireUser` + `canViewVersion` + version hiện hành | form `location?, site_id?` | `print-result.eta` (verify_token + QR SVG + verifyUrl) | `print.no_permission(403), print.no_version, print.not_effective` |
| POST | `/documents/:id/print/recall` | `requireUser` + `canViewVersion` | form `print_id` | 302 `/documents/:id` | `printrecall.not_found, printrecall.no_permission(403)` |
| GET | `/verify/:token` | **CÔNG KHAI (không đăng nhập)** | query `site_id?` | `verify.eta` chỉ TÌNH TRẠNG (không nội dung) | — |

Nguồn: `printing.ts:16-69`. `/verify/:token` là route duy nhất không yêu cầu đăng nhập (`printing.ts:4-6`).

## 9. Gói ban hành (`src/routes/packages.ts`) — **[Legacy]** (IND-02)

> **IND-02 — nhãn [Legacy]:** luồng gói còn theo mô hình CŨ: `attachToPackage` chỉ nhận version `cho_xac_nhan/da_dang_ky` và kích hoạt gói đi qua `da_dang_ky → hieu_luc` (`package.ts`, `fn_activate_due_versions`). Mô hình mới ban hành thẳng `da_ban_hanh` KHÔNG sinh `da_dang_ky` nên các màn hình/route `/packages` chỉ còn tác dụng với dữ liệu legacy; chưa đồng bộ với mô hình receipt. Coi là **[Legacy]** cho tới khi `package.ts` được cập nhật — xem Điểm mở DCS-7.

| Method | Path | Quyền | Request | Response |
|--------|------|-------|---------|----------|
| GET | `/packages` | `requireUser` + `canRegisterDocuments` | — | `packages.eta` |
| POST | `/packages` | như trên | form `code, title, effective_date` | 302 `/packages` |
| POST | `/packages/attach` | như trên | form `version_id, package_id` | 302 `/packages` |
| POST | `/packages/move-date` | như trên | form `package_id, effective_date` | 302 `/packages` |
| POST | `/packages/detach` | như trên | form `version_id, reason` | 302 `/packages` |

Lỗi nghiệp vụ từ `domain/package.ts` (số thành viên ≤10, chỉ gắn gói đang mở, dời ngày — xem DCS-5). Nguồn: `packages.ts:14-106`.

## 10. So sánh phiên bản (`src/routes/diff.ts`)

| Method | Path | Quyền | Request | Response |
|--------|------|-------|---------|----------|
| GET | `/compare` | `requireUser` + `canViewVersion` cả from & to | query `from?, to` | `compare.eta` (reliability + rows); ghi `xem_so_sanh` khi có `from` |

400 thiếu `to`; 404 không xem được. Nguồn: `diff.ts:11-34`.

## 11. Tìm kiếm (`src/routes/search.ts`)

| Method | Path | Quyền | Request | Response |
|--------|------|-------|---------|----------|
| GET | `/search/suggest` | `requireUser` | query `q` (≥2 ký tự) | `search-suggest.eta` (tối đa 5) |
| GET | `/search/results` | `requireUser` | query lọc đa chiều | `search-results-list.eta` (htmx fragment, không audit) |
| GET | `/search` | `requireUser` | query `q, historical, doc_type[], origin, scope_level, eff_from, eff_to, match_level[], sort, bu_id, site_id, dept_id` | `search-results.eta`; ghi audit `tim_kiem` |

Mặc định chỉ bản hiện hành (`da_ban_hanh`/`hieu_luc`); lọc phạm vi là điều kiện truy vấn, không lọc sau (`domain/search.ts:1-7`, `fn_search` `025`). Nguồn: `search.ts:83-130`.

## 12. Nhóm chức năng (`src/routes/functional-groups.ts`)

| Method | Path | Quyền | Request |
|--------|------|-------|---------|
| GET | `/functional-groups` | `hasSystemAdminAccess` | — |
| POST | `/functional-groups` | Admin | `code, name, owner_user_id` |
| POST | `/functional-groups/members/add` | Admin | `group_id, user_id` |
| POST | `/functional-groups/members/remove` | Admin | `group_id, user_id` |

403 nếu không phải Admin. Nguồn: `functional-groups.ts:16-70`.

## 13. Quản trị (`src/routes/admin.ts`) — có hạn sử dụng (gỡ khi L1 lên)

Mọi route đòi `requireSystemAdmin` (DC_ADMIN) → 403 nếu không.

| Method | Path | Request |
|--------|------|---------|
| GET | `/admin/org` | — |
| POST | `/admin/org/bu` | `code, name` |
| POST | `/admin/org/site` | `code, name, bu_id` |
| POST | `/admin/org/site-bu` | `site_id, bu_id` |
| POST | `/admin/org/dept` | `code, name, site_id` |
| GET | `/admin/users` | — |
| POST | `/admin/users` | `email, full_name, password` |
| POST | `/admin/users/assign-dept` | `user_id, dept_id` |
| POST | `/admin/users/remove-dept` | `user_id, dept_id` |
| POST | `/admin/users/active` | `user_id, active` |
| POST | `/admin/users/delete` | `user_id` (soft; chặn tự xoá → 422) |
| GET | `/admin/roles` | — |
| POST | `/admin/roles` | `user_id, role_code, scope_level, scope_bu_id, scope_site_id, scope_dept_id` |

Nguồn: `admin.ts:43-186`.

## 14. Ghi vết (`src/routes/audit.ts`)

| Method | Path | Quyền | Request | Response |
|--------|------|-------|---------|----------|
| GET | `/audit` | `requireUser` + `req.nav.isAdmin` | query `object_id, event_type, from, to` | `audit.eta` |

403 nếu không Admin. Nguồn: `audit.ts:10-28`.

## 15. MCP server (`src/routes/mcp.ts`)

- Endpoint `/mcp` (Streamable HTTP) cho tác nhân AI truy vấn **chỉ-đọc**, luôn lọc quyền theo `user_id` thật của phiên (`mcp.ts:1-8`).
- Danh tính phân giải DUY NHẤT từ cookie phiên đã ký `resolveIdentity`; thiếu/sai → 401 (`mcp.ts:95-110`).
- Giới hạn khối lượng: `DEFAULT_RESULT_LIMIT=20`, `MAXIMUM_RESULT_LIMIT=50`, `SNIPPET_CAP=300` (`mcp.ts:20-26`); `clampLimit`/`capSnippet` (`:35-52`).
- Tái dùng `domain/search.search` + `domain/catalog.canViewVersion` — kết quả AI luôn kèm trích dẫn và không vượt phạm vi người gọi. Chi tiết tích hợp ở DCS-6.
