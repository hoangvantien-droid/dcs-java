package com.dqh.dcs.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.dqh.dcs.catalog.CatalogService;
import com.dqh.dcs.config.AppProperties;
import com.dqh.dcs.registration.VersionInput;
import com.dqh.dcs.search.SearchService;
import com.dqh.dcs.session.SessionService;
import com.dqh.dcs.support.DbSupport;
import com.dqh.dcs.support.ItServices;
import com.dqh.dcs.web.CookieSigner;
import com.dqh.dcs.web.McpController;
import com.dqh.dcs.web.SessionAuthFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * mcp/McpEndpointIT — test tích hợp điểm cuối /mcp (port tinh thần test bản TS,
 * phần CHẠM DB). Dựng McpController THẬT trên jdbc của DbSupport, ký cookie bằng
 * CookieSigner với SESSION_SECRET test. Tự SKIP khi không có DB.
 *
 *  - POST /mcp KHÔNG cookie → 401.
 *  - Phiên hợp lệ → tools/list có 2 tool.
 *  - search_documents tra kết quả trong phạm vi (group doc của author).
 *  - get_document_markdown ngoài phạm vi (dept khác) → notFound GIỐNG HỆT
 *    doc_id không tồn tại (không rò rỉ sự tồn tại).
 */
class McpEndpointIT {

    private static final String SECRET = "test-session-secret-0123456789-abcdefgh";

    private final ObjectMapper om = new ObjectMapper();
    private final List<String> created = new java.util.ArrayList<>();

    private McpController controller;
    private SessionService sessions;
    private CookieSigner signer;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DbSupport.dbOk(), "Không có Docker/DB — bỏ qua IT");
        var jdbc = DbSupport.jdbc();
        SearchService search = new SearchService(jdbc);
        CatalogService catalog = new CatalogService(jdbc);
        McpServer mcp = new McpServer(search, catalog, jdbc);
        this.sessions = new SessionService(jdbc);
        this.signer = new CookieSigner(stubProps(SECRET));
        this.controller = new McpController(mcp, signer, sessions);
    }

    @AfterEach
    void tearDown() {
        if (DbSupport.dbOk()) {
            for (String d : created) {
                DbSupport.cleanupDoc(d);
            }
        }
    }

    /** AppProperties chỉ dùng getSessionSecret() ở CookieSigner — stub tối thiểu. */
    private static AppProperties stubProps(String secret) {
        return new AppProperties(new org.springframework.mock.env.MockEnvironment()
                .withProperty("DATABASE_URL", "postgres://x/y")
                .withProperty("SESSION_SECRET", secret)
                .withProperty("FILE_STORAGE_PATH", "/tmp"));
    }

    private MockHttpServletRequest withSignedCookie(String sessionId) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        if (sessionId != null) {
            req.setCookies(new Cookie(SessionAuthFilter.SESSION_COOKIE, signer.sign(sessionId)));
        }
        return req;
    }

    private JsonNode rpc(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void khongCookieTra401() {
        var resp = controller.post(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"),
                withSignedCookie(null));
        assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    void phienHopLeToolsListCo2Tool() {
        String sessionId = DbSupport.tx().execute(s -> sessions.createSession(DbSupport.seed().authorId));
        var resp = controller.post(rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"),
                withSignedCookie(sessionId));
        assertEquals(200, resp.getStatusCode().value());
        JsonNode result = om.valueToTree(resp.getBody());
        JsonNode tools = result.path("result").path("tools");
        assertEquals(2, tools.size());
        List<String> names = new java.util.ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertTrue(names.contains("search_documents"));
        assertTrue(names.contains("get_document_markdown"));
    }

    @Test
    void searchDocumentsTraKetQuaTrongPhamVi() {
        var seed = DbSupport.seed();
        String docId = "IT-MCP-" + System.currentTimeMillis();
        created.add(docId);
        registerGroupDocWithMarkdown(docId, seed.authorId, "noi dung markdown tim kiem mcp");

        String sessionId = DbSupport.tx().execute(s -> sessions.createSession(seed.authorId));
        String body = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"search_documents\",\"arguments\":{\"q\":\"" + docId + "\"}}}";
        var resp = controller.post(rpc(body), withSignedCookie(sessionId));
        assertEquals(200, resp.getStatusCode().value());
        JsonNode result = om.valueToTree(resp.getBody()).path("result");
        JsonNode results = result.path("structuredContent").path("results");
        assertTrue(results.isArray());
        boolean found = false;
        for (JsonNode r : results) {
            if (docId.equals(r.path("doc_id").asText())) {
                found = true;
            }
        }
        assertTrue(found, "search_documents phải trả tài liệu trong phạm vi của author");
    }

    @Test
    void getMarkdownNgoaiPhamViGiongHetKhongTonTai() {
        var seed = DbSupport.seed();
        String docId = "IT-MCP-OOS-" + System.currentTimeMillis();
        created.add(docId);
        // Tài liệu do author (QA MML) đăng ký, rồi ép scope sang dept HCM (QA2).
        registerGroupDocWithMarkdown(docId, seed.authorId, "noi dung bi mat");
        String hcmDept = DbSupport.plainJdbc().queryForObject(
                "SELECT dept_id::text FROM org_department WHERE dept_code = 'QA2'", String.class);
        DbSupport.tx().execute(s -> {
            DbSupport.plainJdbc().update(
                    "UPDATE documents SET scope_level='dept', scope_dept_id=?::uuid WHERE doc_id=?",
                    hcmDept, docId);
            return null;
        });

        // Người gọi là Reader (QA MML): KHÔNG phải người đăng ký, KHÔNG thuộc QA2,
        // không phải Admin ⇒ ngoài phạm vi THẬT. (Nếu gọi bằng chính author thì
        // nhánh registrant_user_id trong canViewVersion vẫn cho xem tài liệu của
        // mình — không tạo được tình huống ngoài phạm vi.)
        String sessionId = DbSupport.tx().execute(s -> sessions.createSession(seed.readerId));

        // Ngoài phạm vi.
        var oos = markdownCall(docId, sessionId);
        // Không tồn tại.
        var missing = markdownCall("KHONG-TON-TAI-" + System.currentTimeMillis(), sessionId);

        assertTrue(oos.path("isError").asBoolean(), "ngoài phạm vi phải là isError");
        assertTrue(missing.path("isError").asBoolean(), "không tồn tại phải là isError");
        String oosText = oos.path("content").get(0).path("text").asText();
        String missingText = missing.path("content").get(0).path("text").asText();
        assertEquals(McpServer.NOT_FOUND_MESSAGE, oosText);
        assertEquals(oosText, missingText, "hai nhánh phải trả thông điệp GIỐNG HỆT");
    }

    private JsonNode markdownCall(String docId, String sessionId) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"get_document_markdown\",\"arguments\":{\"doc_id\":\"" + docId + "\"}}}";
        var resp = controller.post(rpc(body), withSignedCookie(sessionId));
        assertEquals(200, resp.getStatusCode().value());
        JsonNode result = om.valueToTree(resp.getBody()).path("result");
        assertNotNull(result);
        return result;
    }

    /** Đăng ký doc scope group (author thấy được) + gắn markdown_content. */
    private void registerGroupDocWithMarkdown(String docId, String authorId, String markdown) {
        String today = com.dqh.dcs.common.Clock.isoTodayVN();
        ItServices svc = new ItServices();
        String versionId = DbSupport.tx().execute(s -> svc.registration.submitRegistration(
                ItServices.docInput(docId),
                new VersionInput(docId, "1.0", "Tài liệu MCP " + docId, today, null, today, 12, null,
                        "thu_cong", "/tmp/mcp.pdf", "hash-" + docId, authorId, "", null, null, null),
                List.of(DbSupport.seed().siteIds.get(0))));
        DbSupport.tx().execute(s -> {
            DbSupport.plainJdbc().update(
                    "UPDATE document_versions SET markdown_content = ? WHERE version_id = ?::uuid",
                    markdown, versionId);
            return null;
        });
        // Giữ im cảnh báo nếu versionId không dùng tiếp.
        assertFalse(versionId.isEmpty());
    }
}
