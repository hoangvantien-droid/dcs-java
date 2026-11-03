package com.dqh.dcs.uat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.dqh.dcs.common.Clock;
import com.dqh.dcs.mcp.McpServer;
import com.dqh.dcs.support.DbSupport;
import com.dqh.dcs.support.ItServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * UatSearchMcpIT — tìm kiếm theo scope + MCP qua HTTP THẬT.
 *   UAT-70: GET /search?q=... trả tài liệu trong phạm vi của Author.
 *   UAT-72: POST /mcp search_documents trong phạm vi → có doc_id vừa tạo.
 *   UAT-71 [BẢO MẬT]: get_document_markdown ngoài phạm vi vs không tồn tại →
 *     thông điệp notFound GIỐNG HỆT (không rò rỉ tồn tại).
 *   UAT-73 [BẢO MẬT]: POST /mcp không cookie → 401.
 */
class UatSearchMcpIT extends UatHttpBase {

    private static final ObjectMapper OM = new ObjectMapper();
    private final List<String> createdDocs = new java.util.ArrayList<>();
    private ItServices svc;

    @AfterEach
    void tearDown() {
        for (String d : createdDocs) {
            DbSupport.cleanupDoc(d);
        }
    }

    private ItServices svc() {
        if (svc == null) {
            svc = new ItServices();
        }
        return svc;
    }

    private String registerGroupDocWithMarkdown(String docId, String markdown) {
        createdDocs.add(docId);
        String versionId = DbSupport.tx().execute(s -> svc().registration.submitRegistration(
                ItServices.docInput(docId),
                ItServices.versionInput(docId, DbSupport.seed().authorId, Clock.isoTodayVN()),
                List.of(DbSupport.seed().siteIds.get(0))));
        DbSupport.tx().execute(s -> {
            DbSupport.plainJdbc().update(
                    "UPDATE document_versions SET markdown_content = ? WHERE version_id = ?::uuid",
                    markdown, versionId);
            return null;
        });
        return versionId;
    }

    /** UAT-70: tìm kiếm theo scope → Author thấy tài liệu của mình. */
    @Test
    void timKiemTheoScope() {
        String docId = "UAT-SEARCH-" + System.currentTimeMillis();
        registerGroupDocWithMarkdown(docId, "noi dung tim kiem uat");

        String authorCookie = login(AUTHOR_EMAIL);
        ResponseEntity<String> resp = get("/search?q=" + docId, authorCookie);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody() != null && resp.getBody().contains(docId),
                "kết quả tìm kiếm phải chứa tài liệu trong phạm vi");
    }

    /** UAT-72: MCP search_documents trong phạm vi. */
    @Test
    void mcpSearchTrongPhamVi() throws Exception {
        String docId = "UAT-MCP-" + System.currentTimeMillis();
        registerGroupDocWithMarkdown(docId, "noi dung markdown mcp uat");

        String authorCookie = login(AUTHOR_EMAIL);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"search_documents\",\"arguments\":{\"q\":\"" + docId + "\"}}}";
        ResponseEntity<String> resp = postJson("/mcp", authorCookie, body);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode results = OM.readTree(resp.getBody()).path("result").path("structuredContent").path("results");
        assertTrue(results.isArray());
        boolean found = false;
        for (JsonNode r : results) {
            if (docId.equals(r.path("doc_id").asText())) {
                found = true;
            }
        }
        assertTrue(found, "search_documents phải trả tài liệu trong phạm vi của Author");
    }

    /** UAT-71 [BẢO MẬT]: markdown ngoài phạm vi = không tồn tại (thông điệp giống hệt). */
    @Test
    void mcpMarkdownNgoaiPhamViGiongKhongTonTai() throws Exception {
        String docId = "UAT-MCP-OOS-" + System.currentTimeMillis();
        registerGroupDocWithMarkdown(docId, "noi dung bi mat");
        // Ép scope sang dept HCM (QA2) → Reader (QA MML) ngoài phạm vi.
        String hcmDept = DbSupport.plainJdbc().queryForObject(
                "SELECT dept_id::text FROM org_department WHERE dept_code = 'QA2'", String.class);
        DbSupport.tx().execute(s -> {
            DbSupport.plainJdbc().update(
                    "UPDATE documents SET scope_level='dept', scope_dept_id=?::uuid WHERE doc_id=?",
                    hcmDept, docId);
            return null;
        });

        // Người gọi là Reader (QA MML): ngoài phạm vi THẬT.
        String readerCookie = login(READER_EMAIL);
        JsonNode oos = markdownCall(docId, readerCookie);
        JsonNode missing = markdownCall("KHONG-TON-TAI-" + System.currentTimeMillis(), readerCookie);

        assertTrue(oos.path("isError").asBoolean(), "ngoài phạm vi phải isError");
        assertTrue(missing.path("isError").asBoolean(), "không tồn tại phải isError");
        String oosText = oos.path("content").get(0).path("text").asText();
        String missingText = missing.path("content").get(0).path("text").asText();
        assertEquals(McpServer.NOT_FOUND_MESSAGE, oosText);
        assertEquals(oosText, missingText, "hai nhánh phải trả thông điệp GIỐNG HỆT");
    }

    /** UAT-73 [BẢO MẬT]: POST /mcp không cookie → 401. */
    @Test
    void mcpKhongCookie401() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        ResponseEntity<String> resp = postJson("/mcp", null, body);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    private JsonNode markdownCall(String docId, String cookie) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"get_document_markdown\",\"arguments\":{\"doc_id\":\"" + docId + "\"}}}";
        ResponseEntity<String> resp = postJson("/mcp", cookie, body);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        return OM.readTree(resp.getBody()).path("result");
    }
}
