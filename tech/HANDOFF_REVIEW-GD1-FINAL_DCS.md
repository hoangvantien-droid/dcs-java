# HANDOFF — Review độc lập Giai đoạn 1 DCS (trước khi port sang Java)

> Gửi AI agent review độc lập. Mục tiêu: soát xét toàn bộ Giai đoạn 1 (vá nghiệp vụ + mô hình ngày per-site + timezone + bug + CI) TRƯỚC khi port codebase sang Java/Spring Boot. Specs DCS-1→10 sẽ là bản thiết kế cho bản Java, nên lỗi/thiếu sót trong specs hoặc code sẽ nhân đôi sang Java — hãy bắt trước khi port.

---

## 0. Bối cảnh 60 giây

- **App:** DCS — Hệ Quản lý & Phân phối Tài liệu Điện tử (khâu SAU phê duyệt: đăng ký → ban hành → phân phối → xác nhận đọc → in → tra cứu/hỏi đáp AI có trích dẫn). Soạn thảo/ký duyệt ngoài phạm vi (thuộc QDMS).
- **Stack hiện tại (nguồn sự thật):** Node.js 20 + TypeScript, Fastify 4, driver `postgres` (PostgreSQL 16), template `eta` + HTMX (SSR). Test `node --test` (tsx), build `tsc`. 28 migration SQL.
- **Mô hình nghiệp vụ:** "đăng ký là ban hành luôn" — tài liệu vào thẳng `da_ban_hanh`, không còn mô hình duyệt 2 lớp cũ.
- **Đích sắp tới (GĐ2, CHƯA làm):** port toàn bộ sang Java + Spring Boot + JdbcTemplate, GIỮ NGUYÊN PostgreSQL (không migrate DB), template eta→Thymeleaf, giữ nguyên app.css/HTMX. Review này KHÔNG phải review Java — chưa có code Java.
- **Repo & nhánh:** `d:\Kiro\dcs`, nhánh `feature/catalog-excel-export` (đã push lên GitHub `hoangvantien-droid/DocumentControl`), HEAD `4f59058`.
- **Specs:** `d:\Kiro\dcs-java\tech\DCS-1..DCS-10.md` (repo git RIÊNG tại `d:\Kiro\dcs-java`). "Điểm mở" gom ở DCS-7 §3.
- **Diff toàn GĐ1:** `git -C d:\Kiro\dcs diff c99f060 4f59058` — 55 file, +2746/−297.

### Trạng thái kiểm chứng (QUAN TRỌNG — khác với các handoff trước)

Khác các review trước (chạy trên máy không có Postgres nên integration tự-skip), **GĐ1 nay ĐÃ được kiểm chứng runtime trên PostgreSQL thật qua GitHub Actions CI**:
- **Gate 1 (Integration + unit): 155 tests, 155 pass, 0 fail, 0 cancelled, 0 skip.**
- **Gate 2 (DB schema-rebuild, serial): 27 tests, 27 pass, 0 fail, 0 skip.**
- CI file: `.github/workflows/ci.yml` (Postgres service container `pgvector/pgvector:pg16`, migrate + seed + test).
- Bước thứ ba "Full suite (report-only, `continue-on-error`)": 310 tests, 261 pass, **16 fail, 3 cancelled** — đây là LỖI NỀN CÓ TRƯỚC, ngoài phạm vi GĐ1 (xem §5), KHÔNG làm fail CI.

---

## 1. 12 commit Giai đoạn 1 (phạm vi review)

| Commit | Nội dung |
|---|---|
| `05d78fe` | Vá điểm 1,2,3,4,5,9 — đồng bộ vòng đời mới `da_ban_hanh` + migration 026 |
| `71b556f` | Vá điểm 6,7,8,10 — khóa metadata, apply-date vs effective, report skew + migration 027 |
| `99981f8` | Bảo mật + tài liệu — gỡ mật khẩu DB cứng, sửa số migration, viết lại hướng dẫn |
| `3b80c23` | Test điểm 1–10 |
| `f22002b` | Ngày hiệu lực per-site (2a) + apply_date = ngày hiệu lực khai + migration 028 |
| `575da2c` | Timezone giờ VN — `todayVN()` + TimeZone session Postgres |
| `f5dcb75` | Bug Nhóm A: IND-01, IND-04, IND-05, IND-06 |
| `78979c1` | Hướng 2 — bỏ guard apply_date ≥ effective_date gốc (site hiệu lực độc lập) |
| `48206a5` | CI chạy full test với Postgres service container |
| `31f48c9` | Sửa glob expansion trên bash (globstar) |
| `686fc71` | Sửa seed/fixture khớp schema (app_user_ref là view; tách DROP-SCHEMA chạy serial) |
| `4f59058` | Gỡ cột `approver_name` đã-drop khỏi fixture document_versions |

---

## 2. Các quyết định nghiệp vụ đã chốt với chủ sở hữu (review để chắc specs + code phản ánh đúng)

1. **Timezone = giờ VN (UTC+7).** Mọi mốc "hôm nay" qua helper `todayVN()` (`src/lib/clock.ts`) + TimeZone session Postgres. Không còn `toISOString()` UTC trong guard.
2. **Ngày hiệu lực per-site (phương án 2a):** GIỮ `document_versions.effective_date` làm ngày hiệu lực gốc; THÊM `document_receipt.site_effective_date` (migration 028). Guard per-site đọc `coalesce(site_effective_date, effective_date)`.
3. **apply_date = ngày hiệu lực đã khai cho site đó** (không còn gán `today()` lúc accept). DC Site bấm Chấp nhận = ĐỒNG Ý với ngày đã khai, form accept không có ô nhập ngày.
4. **Thêm site ngày khác** đi qua luồng `publishAdditionalSites` (không mở lại form đăng ký); mỗi site một ngày hiệu lực ĐỘC LẬP (sớm/muộn tùy ý, chỉ cần ≥ issued_date và ≥ hôm nay giờ VN).
5. **Hướng 2:** bỏ guard apply_date ≥ effective_date gốc (vì mâu thuẫn với per-site). Đã gỡ mã lỗi `receipt.apply_before_effective`.
6. **Thu hồi cho phép kể cả khi 100% site đã chấp nhận** (tách `canRecall` khỏi `canEditMetadata`).
7. **`original_effective_date`:** GIỮ cột nhưng ẩn khỏi form đăng ký (dành cho nạp dữ liệu cũ, hiện không migrate).

**Việc cần review:** đọc specs DCS-2/3/4/5 + code để xác nhận cả 7 quyết định này được phản ánh ĐÚNG và NHẤT QUÁN giữa specs ↔ code ↔ test. Đặc biệt soi: khái niệm "tài liệu đã hiệu lực chưa" giờ phụ thuộc site — có chỗ nào còn dùng `effective_date` gốc thay vì per-site không?

---

## 3. Kết luận 4 bug Nhóm A (review phụ vòng trước) — xác minh lại

| Mã | Kết luận GĐ1 | Cần review phụ xác nhận |
|---|---|---|
| IND-01 | BUG THẬT, đã sửa: phiếu của bản `thu_hoi`/`het_hieu_luc` bị loại khỏi hàng chờ/badge + chặn chấp nhận (predicate chung `isReceiptBlockingVersionStatus`) | Có test `ind01-superseded-receipt.integration.test.ts` — xác nhận phủ đúng ngữ nghĩa |
| IND-04 | BUG THẬT (a,b,c), đã sửa: aggregate cảnh báo post-accept; home hiện `contentErrorMine`; tách `canRecall`/`canEditMetadata` | Có test `ind04-content-error.integration.test.ts` — xác nhận 3 nhánh |
| IND-05 | BẤT NHẤT THẬT, đã sửa: bỏ `da_dang_ky` khỏi `CURRENT_VIEW_STATUSES` + 2 IN-list catalog (nhất quán với verifyToken/fn_search) | Xác nhận in + quét QR nay nhất quán |
| IND-06 | KHÔNG phải bug code — chỉ lệch tài liệu: `requireDocFlow` chặn Admin-thuần 403 ở /documents. Đã sửa specs DCS-9 §3 | Xác nhận specs khớp hành vi runtime |

---

## 4. Trọng tâm review (nơi dễ lệch nhất — ưu tiên cao)

1. **Mô hình ngày per-site (rủi ro cao nhất).** `effectiveForSite(site_effective_date, effective_date)` dùng ở `assertCanRespond` (accept), Reader gating (`catalog.ts`), `acceptReceipt` (apply_date). Kiểm: có đường nào CÒN so với `effective_date` gốc thay vì per-site không (sót guard)? Reader site B không thấy trước ngày hiệu lực B nhưng site A vẫn thấy — đúng không?
2. **Timezone giờ VN.** `todayVN()` (Node, qua `Intl`) và TimeZone session Postgres có thực sự cùng mốc? Kiểm biên nửa đêm (00:00–07:00 VN = hôm trước theo UTC): guard hiệu lực/apply/hết hạn có lệch 1 ngày không? Có test `clock.test.ts`.
3. **Kẽ hở "khoảng ngày kẹt" đã vá chưa triệt để?** Hướng 2 gỡ guard apply≥effective gốc. Xác nhận không còn trường hợp site khai ngày hợp lệ mà bị kẹt không chấp nhận được.
4. **Supersede + một-bản-hiện-hành.** Ràng buộc `uq_one_current_per_doc` (migration 026, partial unique index trên `status='da_ban_hanh'`). Thứ tự: retire bản cũ TRƯỚC khi insert bản mới. Kế thừa ack thay đổi "nhỏ" (sao chép) vs "lớn" (đặt lại).
5. **Báo sai nội dung sau chấp nhận (điểm 7).** Trạng thái `bao_sai_noi_dung_sau_chap_nhan` (migration 027): receipt GIỮ `chap_nhan`, report đi đường riêng, aggregate cảnh báo. Kiểm không còn UPDATE no-op gây lệch receipt.
6. **Phân quyền 4 vai trò + scope site** (Admin / Author / DC_SITE_DOC_CONTROL / Reader). Nguyên tắc: lưu bối cảnh bộ phận/nhóm TẠI THỜI ĐIỂM xác nhận (department_id, functional_group_id trong read_acknowledgements), không tra lại L1 sau.

---

## 5. Lỗi nền CÓ TRƯỚC — ngoài phạm vi GĐ1 (đừng tính là finding của GĐ1, nhưng nên ghi nhận cho GĐ2)

Bước "Full suite (report-only)" có 16 fail + 3 cancelled, KHÔNG do GĐ1 gây ra:
- `test/views/render.test.ts`: ~15 fail — phân giải layout template thư mục con (confirm.eta/recall.eta/admin/*) + 1 khẳng định ASI ở home.eta. Có từ trước GĐ1.
- `test/uat/harness.ts`: import `./src/routes/confirmation.js` KHÔNG tồn tại (route đã gộp vào receipts.ts) → `http.uat.test.ts` vỡ lúc import.

**Lưu ý cho GĐ2:** khi port sang Java, các lỗi template/route này sẽ phải được giải quyết (hoặc quyết định bỏ test cũ) — nên đưa vào danh mục nợ kỹ thuật.

---

## 6. Đối chiếu specs ↔ code (các điểm review phụ vòng trước đã nêu — xác nhận đã xử lý)

- **IND-02:** `/packages` (gói ban hành) chưa đồng bộ mô hình mới — đã gắn nhãn Legacy trong specs DCS-3/4/7. Xác nhận còn đúng.
- **IND-03:** `POST /site-receipts/:id/accept` không nhận apply_date từ input; apply_date = site_effective_date. Specs DCS-3 §5 + DCS-4 §11 đã cập nhật.
- **IND-07:** `document_links` (nhãn QDMS) + `distribution_matrix` — có bảng/trigger nhưng thiếu API/UI quản lý → ghi ở Điểm mở DCS-7.
- **F3:** số migration — specs ghi rõ "thực nhánh = 28" vs baseline cũ. Xác nhận không gọi baseline là "lạc hậu".
- **F5:** nhãn trạng thái kiểm chứng — **CẬP NHẬT:** nay phần lớn integration đã `[đã kiểm chứng test DB qua CI]` thay vì `[chưa kiểm chứng runtime]`. Kiểm specs có cập nhật nhãn không.

---

## 7. Hai việc THỦ CÔNG của chủ sở hữu (ngoài phạm vi code — chỉ nhắc, đừng tự làm)

1. **Rotate secret trên server:** DB password + `SESSION_SECRET` (vì `dcs2026secure` từng nằm trong script deploy, nay đã gỡ khỏi HEAD nhưng còn trong lịch sử).
2. **Thu hồi Personal Access Token** đã dùng để đọc CI (nếu còn hiệu lực).

---

## 8. Cách báo cáo review độc lập

Trả về:
- Xác nhận 7 quyết định nghiệp vụ (§2) được phản ánh đúng + nhất quán specs/code/test, hoặc chỉ ra chỗ lệch (kèm file:dòng).
- Kết luận 6 trọng tâm §4 — đúng/sai theo code thật.
- Bất kỳ lệch hành vi / kẽ hở MỚI nào (kèm file:dòng + đề xuất).
- Đánh giá specs DCS-1→10 đã SẴN SÀNG làm bản thiết kế để port Java chưa — thiếu gì cần bổ sung trước khi port (vd: công thức SQL của view/hàm PL/pgSQL, schema bảng phụ, danh mục MCP tool — các điểm review phụ trước từng nêu là "port blind spots").
- KHÔNG đổi stack, KHÔNG port Java (đó là GĐ2).

---

## Phụ lục — lệnh mồi

```bash
git -C d:\Kiro\dcs diff c99f060 4f59058          # diff toàn GĐ1
git -C d:\Kiro\dcs log --oneline c99f060..HEAD   # 12 commit
# CI đã xanh: xem .github/workflows/ci.yml + Actions tab trên GitHub
# Specs: dcs-java/tech/DCS-1..10.md ; Điểm mở: DCS-7 §3
# Artifact review nội bộ: dcs/.agents/tasks/dcs-persite-effective-and-fixes/
```
