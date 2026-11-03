# HANDOFF — Review phụ Giai đoạn 1 DCS (đồng bộ vòng đời mới + test điểm 1–10 + specs)

> Gửi coding agent review phụ. Mục tiêu: kiểm chứng độc lập phần vá + test của GĐ1, tập trung vào những chỗ máy chạy workflow KHÔNG kiểm được (không có PostgreSQL) và các lệch hành vi "possible" mà review chính đã nêu nhưng chưa sửa. Đây KHÔNG phải yêu cầu port sang Java (đó là GĐ2).

---

## 0. Bối cảnh 30 giây

- **App:** DCS — Hệ Quản lý & Phân phối Tài liệu Điện tử.
- **Stack thật (nguồn sự thật):** Node.js + TypeScript, Fastify 4, driver `postgres` (PostgreSQL), template `eta` + HTMX (SSR). Test: `node --test` (tsx). Build: `tsc`.
  - *Lưu ý:* một số tài liệu/prompt nhắc "Java + Spring Boot + MySQL" — đó là ĐÍCH của GĐ2 (port), KHÔNG phải stack hiện tại. Code hiện tại là TypeScript. Đừng review như thể là Java.
- **Mô hình nghiệp vụ mới:** "đăng ký là ban hành luôn" — tài liệu khi đăng ký vào thẳng trạng thái `da_ban_hanh`, không còn mô hình duyệt 2 lớp cũ (`hieu_luc`). GĐ1 vá các luồng nền còn chạy theo `hieu_luc` cũ.
- **Repo & nhánh cần review:** `d:\Kiro\dcs`, nhánh `feature/catalog-excel-export`.
- **Diff cần review:** `git -C d:\Kiro\dcs diff 00b2174 f515eb4` (4 commit, 34 file, +1896/−227).
- **Specs tham chiếu:** `d:\Kiro\dcs-java\tech\DCS-1..DCS-10` (repo git riêng tại `d:\Kiro\dcs-java`, commit `b0ba456`). "Điểm mở" gom ở **DCS-7 §3**.

### 4 commit trong phạm vi review

| Commit | Nội dung | Điểm review |
|---|---|---|
| `ed243e4` | Đồng bộ vòng đời mới (`da_ban_hanh`) + migration 026 | 1, 2, 3, 4, 5, 9 |
| `3e08033` | Khóa metadata, apply-date vs effective, report skew + migration 027 | 6, 7, 8, 10 |
| `edca6ee` | Bảo mật + tài liệu (gỡ mật khẩu DB cứng, sửa migration 16→25, viết lại hướng dẫn) | security/docs |
| `f515eb4` | Test điểm 1–10 (1 unit + 10 integration tự-skip) | tests |

---

## 1. ƯU TIÊN CAO NHẤT — chạy nhóm test DB-integration trên PostgreSQL thật

Máy chạy workflow KHÔNG có Postgres local → **10/10 file integration tự-skip** (tổng thể: `test:unit` 107 pass / 0 fail / **34 skip**). Đây là lỗ hổng kiểm chứng lớn nhất: phần lớn logic vá nằm ở tầng DB + SQL (2 migration, hàm PL/pgSQL, ràng buộc chỉ mục) **chưa từng chạy thực tế**.

**Việc cần làm:**
1. Dựng PostgreSQL, set biến môi trường `DATABASE_URL_TEST` (xem pattern trong `test/domain/_lifecycle-db.ts`).
2. Chạy toàn bộ: `npm test` (không chỉ `test:unit`). Kỳ vọng: 0 fail, và **34 test skip kia phải thực sự chạy và xanh**.
3. Chạy riêng migrate để chắc 026/027 apply sạch trên schema đi từ 001: `npm run migrate`.

**Các file integration phải xanh (không được còn skip):**
`test/domain/supersede-lifecycle.integration.test.ts`, `review-scan.integration.test.ts`, `print-ack-current.integration.test.ts`, `future-effective.integration.test.ts`, `recall-pending.integration.test.ts`, `accepted-lock.integration.test.ts`, `report-after-accept.integration.test.ts`, `apply-date.integration.test.ts`, `edit-metadata-guards.integration.test.ts`, `receipt-model.integration.test.ts`.

> Nếu không dựng được Postgres: nêu rõ trong báo cáo rằng toàn bộ tầng DB của GĐ1 vẫn CHƯA được kiểm chứng runtime — đây là rủi ro còn treo, không được coi là "đã test".

---

## 2. HAI FINDING "possible" review chính nêu nhưng CHƯA sửa — cần kết luận

### Finding #1 (QUAN TRỌNG) — Phiếu còn tồn của bản bị supersede

Khi một bản bị **supersede** → chuyển sang `het_hieu_luc`, nhưng phiếu `cho_phan_hoi` của bản cũ:
- **Vẫn** nằm trong `pendingReceiptsForSites` / `pendingReceiptCountForSites` (chúng chỉ loại trạng thái `thu_hoi`, không loại `het_hieu_luc`).
- `assertCanRespond` **chỉ** chặn `thu_hoi`, **không** chặn `het_hieu_luc`.
- → Về lý thuyết DC Site vẫn thấy trong hàng chờ/badge và **vẫn bấm "Chấp nhận" được** phiếu của một bản đã bị thay thế.

Đây là lệch cạnh của điểm 1/5 (điểm 5 gốc chỉ nói về *thu hồi*). **Cần kiểm chứng bằng test:**
- Viết 1 test integration: tạo doc có bản A (`da_ban_hanh`) + phiếu `cho_phan_hoi` cho 1 site → supersede bằng bản B → **khẳng định** phiếu của A không còn trong `pendingReceiptCountForSites` VÀ `assertCanRespond` ném lỗi khi site cố chấp nhận phiếu A.
- Nếu test đỏ → đây là bug thật: cần (a) loại `het_hieu_luc` khỏi hàng chờ/badge và (b) cho `assertCanRespond` chặn `het_hieu_luc` (tương tự cách đã làm với `thu_hoi`).
- **Câu hỏi nghiệp vụ cần chốt với người dùng:** luồng supersede có thực sự để lại phiếu `cho_phan_hoi` trên bản cũ không, hay thực tế chúng đã được chấp nhận/từ chối trước khi ra bản mới? Trả lời cái này quyết định có cần vá hay chỉ ghi nhận.

Vị trí code liên quan: `src/domain/receipt.ts` (`assertCanRespond`, `pendingReceiptsForSites`, `pendingReceiptCountForSites`), `src/domain/registration.ts` (`submitRegistration` đường supersede).

### Finding #2 — Biên ngày UTC vs múi giờ server

Mọi so sánh ngày (điểm 4/8/10) dùng **ngày UTC** nhất quán:
- `today()` ở `receipt.ts` ≈ `new Date().toISOString().slice(0,10)`
- `isEffectiveNow` ở `lifecycle-status.ts`
- view `receipt-respond.eta`
- phía SQL: `effective_date <= CURRENT_DATE`

Nhất quán nội bộ (không lệch chéo giữa các kiểm tra), **nhưng** trên server đặt múi giờ VN (UTC+7), biên "hôm nay" có thể lệch 1 ngày so với lịch địa phương quanh nửa đêm (0h–7h sáng giờ VN vẫn là "hôm qua" theo UTC).

**Cần làm:**
- Xác nhận múi giờ dự kiến của DB server và app server khi deploy. `CURRENT_DATE` của Postgres phụ thuộc `timezone` của session/DB — nếu DB chạy UTC còn người dùng nghĩ theo giờ VN thì biên ngày hiệu lực/apply-date/hết hạn lệch 1 ngày.
- Đề xuất test: giả lập thời điểm quanh nửa đêm (vd 16:30 UTC = 23:30 VN, và 17:30 UTC = 00:30 VN hôm sau) để xem một tài liệu `effective_date` = "ngày VN hôm nay" có bị coi là tương lai theo UTC không.
- Kết luận: hoặc (a) chấp nhận và ghi rõ giả định "toàn hệ dùng UTC" vào tài liệu vận hành, hoặc (b) chuyển sang mốc ngày theo múi giờ cấu hình. Hiện đã nêu một phần ở DCS-7 Điểm mở.

---

## 3. Điểm theo từng review point — kiểm chứng hành vi (không chỉ đọc code)

| Điểm | Khẳng định cần kiểm | Vị trí code chính |
|---|---|---|
| 1 Supersede | Bản gốc chuyển `het_hieu_luc` **TRƯỚC** khi INSERT bản `da_ban_hanh` mới (nếu đảo thứ tự sẽ vi phạm `uq_one_current_per_doc`). Kế thừa ack: thay đổi "nho" sao chép `read_acknowledgements`, "lon" KHÔNG kế thừa. | `registration.ts` `submitRegistration`; `026` chỉ mục `uq_one_current_per_doc` |
| 2 Job rà soát | `fn_generate_due_reviews` quét `status IN ('da_ban_hanh','hieu_luc')` → filter "Quá hạn xem xét" không rỗng với tài liệu mô hình mới | `026_lifecycle_sync.sql` |
| 3 In + ack | `registerPrint` dùng `!isCurrentlyEffective(v.status)` thay vì so cứng `=== 'hieu_luc'`; in được `da_ban_hanh`, bị chặn khi `thu_hoi` | `printing.ts`, `routes/catalog.ts`, `detail.eta` |
| 4 Ngày tương lai | 3 tầng gating không chồng chéo: `listVisibleDocuments`/`canViewVersion` (SQL), `assertCanRespond(accept)` ném `receipt.before_effective`, view ẩn nút. **`reject` VẪN cho phép** trước ngày hiệu lực (có chủ đích) | `catalog.ts`, `receipt.ts`, `lifecycle-status.ts`, `receipt-respond.eta` |
| 5 Thu hồi | `assertCanRespond` ném `receipt.recalled` khi `thu_hoi`; `pendingReceipts*` JOIN để loại `thu_hoi` khỏi list + badge | `receipt.ts` `recallByAuthor` |
| 6 Khóa metadata | `assertNotAcceptedLocked` + mã lỗi chung `issue.locked_accepted` dùng cho CẢ `editVersionMetadata` VÀ `publishAdditionalSites`; `classifySitesForRepublish` chặn khi có từ chối `sai_noi_dung` | `receipt.ts` |
| 7 Báo sai sau chấp nhận | `convertReportToRejection`: `chap_nhan`+`sai_dang_ky` → chặn (`report.already_accepted`); `chap_nhan`+`sai_noi_dung` → report sang `bao_sai_noi_dung_sau_chap_nhan`, GIỮ receipt `chap_nhan`, audit riêng; không còn UPDATE no-op gây lệch | `issue-report.ts`, `027`, `lib/audit.ts` |
| 8 apply_date | `acceptReceipt` chặn `applyDate < effective_date` (`receipt.apply_before_effective`) | `receipt.ts` |
| 9 Hết hạn chỉ cảnh báo | `fn_activate_due_versions` đã GỠ khối tự chuyển quá `expiry_date` → `het_hieu_luc`; chỉ còn cảnh báo (catalog preset `expired` + banner) | `026`, `detail.eta` |
| 10 Sửa metadata | 3 trạng thái expiry: **vắng** (`undefined` → GIỮ, coalesce) / **rỗng** (`''` → XÓA NULL) / **có giá trị** (ghi đè). Route phân biệt vắng/rỗng bằng `hasOwnProperty`. Chặn `effective_date` quá khứ (`edit.effective_past`) | `documents.ts`, `receipt.ts` `editVersionMetadata` |

**Chỗ dễ sai nhất cần soi kỹ:** điểm 10 (ba trạng thái của `expiry_date`). Hãy viết thêm test đảm bảo: submit form KHÔNG có field expiry → hạn cũ GIỮ NGUYÊN (không bị null hóa — đây chính là bug gốc); submit field expiry rỗng → hạn bị xóa; submit có giá trị → ghi đè. Kiểm cả ở tầng route (`hasOwnProperty`) lẫn tầng domain (sentinel `''`).

---

## 4. Điểm 11 (mẫu chuẩn) — KHÔNG có code, đừng tìm bug ở đây

`src/` hoàn toàn không có `reference_sample | can_thay_mau | lay_mau | mau_chuan`. Mâu thuẫn FR-05/FR-06 (tạo yêu cầu lấy mẫu thủ công có chuyển mẫu sang "Cần thay mẫu" không; đường quay "Cần thay mẫu" → "Có hiệu lực") chỉ tồn tại trên giấy. Đã ghi ở **DCS-7 §Điểm mở**. Review phụ chỉ cần xác nhận specs mô tả đúng là "chưa implement", KHÔNG được bịa code hay đánh dấu là bug.

---

## 5. Bảo mật & tài liệu — kiểm chứng nhanh

- `deploy/deploy-tienhv.sh`: đã thay `DB_PASS=dcs2026secure` cứng bằng lookup env bắt buộc. **Kiểm:** `git -C d:\Kiro\dcs grep -n dcs2026secure` phải không ra kết quả trong code hiện hành (chỉ có thể còn trong lịch sử git).
- Chỉ `.env.example` được track, không `.env` thật: `git -C d:\Kiro\dcs ls-files | Select-String "\.env"` → chỉ thấy `.env.example`.
- Số migration: README.md + docs/STATUS.md đã sửa 16→25. **Lưu ý khác biệt có chủ đích:** tài liệu ghi **25** (theo baseline người dùng), nhưng nhánh thực tế mang tới **027** (do FEAT-001/002 thêm 026, 027). Đây không phải lỗi — cần nêu rõ con số nào là "thực tế trên nhánh" (27) vs "baseline tài liệu" (25) để tránh nhầm.
- Hướng dẫn `docs/HUONG-DAN-SU-DUNG.md` đã viết lại theo mô hình receipt 4 vai trò (Admin / Author / DC_SITE_DOC_CONTROL / Reader). Kiểm còn sót mô tả "duyệt 2 lớp" cũ không.

---

## 6. Hai việc THỦ CÔNG của người dùng (ngoài phạm vi code — chỉ nhắc lại, đừng tự làm)

1. **Rotate secret trên server:** đổi mật khẩu DB + `SESSION_SECRET`, vì `dcs2026secure` từng nằm trong script deploy (coi như đã lộ nếu script từng gửi ra ngoài). Agent KHÔNG được tự đụng secret vận hành.
2. Chạy nhóm test DB-integration ở nơi có PostgreSQL (xem §1).

---

## 7. Cách báo cáo kết quả review phụ

Trả về:
- Kết quả `npm test` **đầy đủ** trên Postgres thật (số pass/fail/skip; nếu còn skip, giải thích tại sao).
- Kết luận Finding #1 (phiếu supersede): có phải bug không, kèm test chứng minh.
- Kết luận Finding #2 (timezone): giả định múi giờ deploy + có cần vá không.
- Mọi lệch hành vi mới phát hiện (kèm `file:dòng`).
- Xác nhận các khẳng định ở §3 đúng/sai theo hành vi runtime, không chỉ đọc code.
- KHÔNG đổi stack, KHÔNG port sang Java (đó là GĐ2 riêng).

---

## Phụ lục — lệnh mồi

```bash
# Xem diff toàn phần
git -C d:\Kiro\dcs diff 00b2174 f515eb4

# Build + test (cần Postgres cho integration)
cd d:\Kiro\dcs
npm run build
npm run test:unit      # chạy không cần DB
# đặt DATABASE_URL_TEST rồi:
npm test               # full, gồm integration

# Artifact review chính (đọc để không lặp công)
#   d:\Kiro\dcs\.agents\tasks\dcs-lifecycle-sync-specs\review.md
#   d:\Kiro\dcs\.agents\tasks\dcs-lifecycle-sync-specs\review.json
```
