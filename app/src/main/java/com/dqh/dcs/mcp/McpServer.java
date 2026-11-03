package com.dqh.dcs.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.dqh.dcs.catalog.CatalogService;
import com.dqh.dcs.search.SearchFilters;
import com.dqh.dcs.search.SearchHit;
import com.dqh.dcs.search.SearchService;

/**
 * mcp/McpServer — máy chủ MCP nhúng (DCS_MCP_Server), port src/routes/mcp.ts.
 *
 * Phơi điểm cuối {@code /mcp} cho tác nhân AI truy vấn kho tài liệu ở chế độ CHỈ
 * ĐỌC, luôn lọc quyền theo {@code user_id} THẬT của phiên. Toàn bộ logic nghiệp
 * vụ tái dùng domain đã có (SearchService.fn_search_limited, CatalogService.
 * canViewVersion); lớp này chỉ ánh xạ yêu cầu MCP sang hàm domain và định dạng
 * kết quả theo giao thức.
 *
 * QĐ triển khai: KHÔNG dùng SDK Java chính thức (io.modelcontextprotocol.sdk).
 * SDK đó gắn chặt với transport servlet/reactive có quản lý phiên và đăng ký
 * tool MỘT LẦN ở cấp server — không hợp với yêu cầu stateless + bind userId
 * THEO TỪNG yêu cầu (bản TS dựng server mới cho mỗi POST). Thay vào đó tự hiện
 * giao thức JSON-RPC 2.0 tối thiểu của MCP (initialize / tools/list / tools/call
 * / resources/list / resources/read) đủ để tool AI gọi được. Không thêm phụ
 * thuộc, giữ stateless, userId bind vào tham số từng lời gọi (không biến toàn
 * cục).
 *
 * Các hàm thuần (clampLimit, capSnippet, mapSearchHits) tách riêng để unit test
 * không cần DB.
 */
@Service
public class McpServer {

    // === Hằng số giới hạn khối lượng kết quả (Requirement 6) ===

    /** Số kết quả trả về mặc định khi client không chỉ định limit. */
    public static final int DEFAULT_RESULT_LIMIT = 20;
    /** Trần số kết quả tối đa trong một lần gọi. */
    public static final int MAXIMUM_RESULT_LIMIT = 50;
    /** Giới hạn độ dài đoạn trích (snippet) của mỗi kết quả: 300 ký tự. */
    public static final int SNIPPET_CAP = 300;

    /**
     * Thông điệp cố định cho "không tìm thấy HOẶC không có quyền". Cố ý gộp hai
     * khả năng (không tồn tại vs ngoài phạm vi) làm một để không rò rỉ sự tồn tại
     * của tài liệu (Requirement 4.3, 5.4).
     */
    public static final String NOT_FOUND_MESSAGE =
            "Không tìm thấy tài liệu khớp định danh, hoặc bạn không có quyền xem.";

    /** Tên + phiên bản server báo trong initialize (khớp bản TS). */
    public static final String SERVER_NAME = "dcs-mcp";
    public static final String SERVER_VERSION = "0.1.0";
    /** Phiên bản giao thức MCP báo cho client. */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    private final SearchService search;
    private final CatalogService catalog;
    private final NamedParameterJdbcTemplate jdbc;

    public McpServer(SearchService search, CatalogService catalog, NamedParameterJdbcTemplate jdbc) {
        this.search = search;
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    // === Hàm thuần (tách riêng để unit test không cần DB) ===

    /**
     * Chuẩn hoá {@code limit} về khoảng [1, MAXIMUM_RESULT_LIMIT].
     * null → DEFAULT_RESULT_LIMIT; làm tròn xuống; dưới 1 → 1; vượt trần → trần.
     */
    public static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_RESULT_LIMIT;
        }
        int n = limit;
        if (n < 1) {
            return 1;
        }
        if (n > MAXIMUM_RESULT_LIMIT) {
            return MAXIMUM_RESULT_LIMIT;
        }
        return n;
    }

    /**
     * Cắt snippet về tối đa SNIPPET_CAP ký tự; kết quả luôn là tiền tố của chuỗi
     * gốc. Trả {@code ""} khi đầu vào null.
     */
    public static String capSnippet(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() <= SNIPPET_CAP ? s : s.substring(0, SNIPPET_CAP);
    }

    /** DTO một kết quả tìm kiếm trả về client MCP (ánh xạ 1-1 từ SearchHit). */
    public record SearchResultItem(
            String doc_id, String version_id, String version_no, String title, String status,
            String section_code, String section_title, String match_level, double rank, String snippet) {
    }

    /**
     * Ánh xạ danh sách SearchHit sang DTO. CHỈ ánh xạ trường + cắt snippet; KHÔNG
     * lọc lại, KHÔNG thêm/bớt phần tử (lọc quyền đã nằm trong fn_search_limited).
     * Giữ nguyên match_level (document | section | fulltext).
     */
    public static List<SearchResultItem> mapSearchHits(List<SearchHit> hits) {
        List<SearchResultItem> out = new ArrayList<>(hits.size());
        for (SearchHit h : hits) {
            out.add(new SearchResultItem(h.docId(), h.versionId(), h.versionNo(), h.title(), h.status(),
                    h.sectionCode(), h.sectionTitle(), h.matchLevel(), h.rank(), capSnippet(h.snippet())));
        }
        return out;
    }

    /** Định dạng kết quả tìm kiếm thành văn bản dễ đọc cho mô hình. */
    public static String formatSearchText(List<SearchResultItem> rows) {
        if (rows.isEmpty()) {
            return "Không có kết quả nào khớp.";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            SearchResultItem r = rows.get(i);
            String section = r.section_code() != null
                    ? " · mục " + r.section_code()
                        + (r.section_title() != null ? " (" + r.section_title() + ")" : "")
                    : "";
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(i + 1).append(". [").append(r.match_level()).append("] ").append(r.title())
              .append(" — ").append(r.doc_id()).append(" v").append(r.version_no())
              .append(" (").append(r.status()).append(')').append(section);
            if (r.snippet() != null && !r.snippet().isEmpty()) {
                sb.append("\n   ").append(r.snippet());
            }
        }
        return sb.toString();
    }

    // === Truy vấn domain (lọc quyền theo userId phiên) ===

    /**
     * Tool {@code search_documents}: gọi fn_search_limited với userId của phiên,
     * clamp limit, ánh xạ + cắt snippet. KHÔNG lọc lại ở tầng MCP.
     */
    public List<SearchResultItem> searchDocuments(String userId, String q, boolean includeHistorical,
            Integer limit) {
        List<SearchHit> hits = search.search(userId, q, includeHistorical, SearchFilters.empty(),
                clampLimit(limit));
        return mapSearchHits(hits);
    }

    /**
     * Hàm dùng chung cho tool {@code get_document_markdown} lẫn resource
     * {@code dcs://document/{id}}: trả Markdown bản hiệu lực KHI VÀ CHỈ KHI người
     * gọi được phép xem; mọi trường hợp khác (không tồn tại / ngoài phạm vi /
     * chưa có markdown) đều trả {@code null}. Một đường quyết định duy nhất ⇒ hai
     * lối đọc markdown không thể lệch nhau.
     *
     * Chỉ truy vấn ĐỌC: v_current_documents, canViewVersion, document_versions.
     */
    public String readMarkdownIfAllowed(String userId, String docId) {
        List<String> ver = jdbc.query(
                "SELECT version_id FROM v_current_documents WHERE doc_id = :doc_id",
                new MapSqlParameterSource("doc_id", docId),
                (rs, i) -> rs.getString("version_id"));
        if (ver.isEmpty() || ver.get(0) == null) {
            return null; // không có bản hiệu lực
        }
        String versionId = ver.get(0);
        if (!catalog.canViewVersion(userId, versionId)) {
            return null; // ngoài phạm vi
        }
        List<String> md = jdbc.query(
                "SELECT markdown_content FROM document_versions WHERE version_id = CAST(:v AS uuid)",
                new MapSqlParameterSource("v", versionId),
                (rs, i) -> rs.getString("markdown_content"));
        return md.isEmpty() ? null : md.get(0); // chưa có markdown → null
    }

    // === Siêu dữ liệu tool/resource (cho tools/list, resources/list) ===

    /** Mô tả hai tool (dùng cho tools/list). */
    public static List<Map<String, Object>> toolDescriptors() {
        Map<String, Object> searchTool = Map.of(
                "name", "search_documents",
                "title", "Tìm kiếm tài liệu",
                "description", "Tìm kiếm tài liệu trong phạm vi quyền của bạn (chỉ đọc). "
                        + "Trả về danh sách kết quả kèm đoạn trích.",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "q", Map.of("type", "string", "description", "Từ khoá tìm kiếm"),
                                "include_historical", Map.of("type", "boolean",
                                        "description", "Bao gồm cả bản không còn hiệu lực mà bạn được phép xem"),
                                "limit", Map.of("type", "integer",
                                        "description", "Số kết quả tối đa (mặc định 20, trần 50)")),
                        "required", List.of("q")));
        Map<String, Object> markdownTool = Map.of(
                "name", "get_document_markdown",
                "title", "Lấy nội dung Markdown của tài liệu",
                "description", "Trả nội dung Markdown bản hiệu lực của một tài liệu nếu bạn có quyền "
                        + "xem (chỉ đọc).",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "doc_id", Map.of("type", "string",
                                        "description", "Số hiệu / định danh tài liệu")),
                        "required", List.of("doc_id")));
        return List.of(searchTool, markdownTool);
    }

    /** Mô tả resource template (dùng cho resources/list). */
    public static List<Map<String, Object>> resourceTemplateDescriptors() {
        return List.of(Map.of(
                "uriTemplate", "dcs://document/{id}",
                "name", "document",
                "description", "Nội dung Markdown bản hiệu lực của một tài liệu",
                "mimeType", "text/markdown"));
    }
}
