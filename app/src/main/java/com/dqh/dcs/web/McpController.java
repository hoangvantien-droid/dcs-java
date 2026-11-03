package com.dqh.dcs.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dqh.dcs.mcp.McpServer;
import com.dqh.dcs.mcp.McpServer.SearchResultItem;
import com.dqh.dcs.session.SessionService;
import com.dqh.dcs.session.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * web/McpController — điểm cuối /mcp (port src/routes/mcp.ts).
 *
 * Máy chủ MCP Streamable HTTP, STATELESS: mỗi POST là một yêu cầu JSON-RPC độc
 * lập (không sinh session MCP). CHỈ ĐỌC, luôn lọc quyền theo user_id THẬT của
 * phiên. GET /mcp → 405 (không có kênh SSE server→client).
 *
 * Danh tính phân giải TỪ cookie đã ký 'dcs_sid' (tái dùng CookieSigner +
 * SessionService của FEAT-003) — nơi DUY NHẤT lấy userId. Thiếu cookie / chữ ký
 * sai / phiên hết hạn / người dùng ngừng hoạt động → 401, KHÔNG khởi tạo MCP.
 *
 * QĐ triển khai: tự hiện giao thức JSON-RPC 2.0 tối thiểu của MCP thay vì dùng
 * SDK Java chính thức — xem McpServer để biết lý do. Giữ stateless; userId bind
 * vào tham số từng lời gọi domain (không biến toàn cục).
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private final McpServer mcp;
    private final CookieSigner signer;
    private final SessionService sessions;

    public McpController(McpServer mcp, CookieSigner signer, SessionService sessions) {
        this.mcp = mcp;
        this.signer = signer;
        this.sessions = sessions;
    }

    // === GET /mcp → 405 (stateless, không SSE) ===

    @GetMapping
    public ResponseEntity<Map<String, String>> get() {
        return ResponseEntity.status(405).body(Map.of("error", "method_not_allowed"));
    }

    // === POST /mcp → JSON-RPC MCP ===

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> post(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        SessionUser user = resolveIdentity(request);
        if (user == null) {
            // Thiếu/không hợp lệ danh tính → 401, KHÔNG khởi tạo MCP.
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        if (body == null || !body.isObject()) {
            return ResponseEntity.ok(jsonRpcError(null, -32700, "Parse error"));
        }
        Object result = handleRpc(body, user.userId());
        return ResponseEntity.ok(result);
    }

    /**
     * Phân giải danh tính người gọi từ cookie phiên đã ký — nơi DUY NHẤT lấy
     * userId. Trả null (caller → 401) trong mọi trường hợp không xác thực được.
     */
    private SessionUser resolveIdentity(HttpServletRequest request) {
        String raw = readCookie(request, SessionAuthFilter.SESSION_COOKIE);
        if (raw == null) {
            return null; // thiếu cookie
        }
        String sessionId = signer.unsign(raw);
        if (sessionId == null || sessionId.isEmpty()) {
            return null; // chữ ký sai → vô danh
        }
        return sessions.resolveSession(sessionId); // null: hết hạn / ngừng hoạt động
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    // === Điều phối JSON-RPC ===

    private Object handleRpc(JsonNode req, String userId) {
        JsonNode idNode = req.get("id");
        Object id = idNode == null || idNode.isNull() ? null
                : (idNode.isNumber() ? idNode.numberValue() : idNode.asText());
        String method = req.path("method").asText(null);
        JsonNode params = req.get("params");

        if (method == null) {
            return jsonRpcError(id, -32600, "Invalid Request");
        }
        try {
            switch (method) {
                case "initialize":
                    return jsonRpcResult(id, initializeResult());
                case "ping":
                    return jsonRpcResult(id, Map.of());
                case "tools/list":
                    return jsonRpcResult(id, Map.of("tools", McpServer.toolDescriptors()));
                case "tools/call":
                    return jsonRpcResult(id, callTool(params, userId));
                case "resources/list":
                    return jsonRpcResult(id, Map.of("resources", List.of()));
                case "resources/templates/list":
                    return jsonRpcResult(id, Map.of("resourceTemplates",
                            McpServer.resourceTemplateDescriptors()));
                case "resources/read":
                    return jsonRpcResult(id, readResource(params, userId));
                default:
                    return jsonRpcError(id, -32601, "Method not found: " + method);
            }
        } catch (McpResourceException e) {
            // resources/read khi không được phép → lỗi JSON-RPC với thông điệp GIỐNG HỆT tool.
            return jsonRpcError(id, -32002, e.getMessage());
        } catch (IllegalArgumentException e) {
            return jsonRpcError(id, -32602, e.getMessage());
        }
    }

    private Map<String, Object> initializeResult() {
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("tools", Map.of());
        cap.put("resources", Map.of());
        return Map.of(
                "protocolVersion", McpServer.PROTOCOL_VERSION,
                "capabilities", cap,
                "serverInfo", Map.of("name", McpServer.SERVER_NAME, "version", McpServer.SERVER_VERSION));
    }

    // === tools/call ===

    private Map<String, Object> callTool(JsonNode params, String userId) {
        if (params == null) {
            throw new IllegalArgumentException("Thiếu params");
        }
        String name = params.path("name").asText(null);
        JsonNode args = params.get("arguments");
        if (name == null) {
            throw new IllegalArgumentException("Thiếu tên tool");
        }
        switch (name) {
            case "search_documents":
                return searchDocumentsCall(args, userId);
            case "get_document_markdown":
                return getMarkdownCall(args, userId);
            default:
                throw new IllegalArgumentException("Tool không tồn tại: " + name);
        }
    }

    private Map<String, Object> searchDocumentsCall(JsonNode args, String userId) {
        if (args == null || !args.hasNonNull("q")) {
            throw new IllegalArgumentException("Thiếu tham số bắt buộc 'q'");
        }
        String q = args.get("q").asText();
        boolean includeHistorical = args.hasNonNull("include_historical")
                && args.get("include_historical").asBoolean(false);
        Integer limit = args.hasNonNull("limit") ? args.get("limit").asInt() : null;

        List<SearchResultItem> results = mcp.searchDocuments(userId, q, includeHistorical, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(textContent(McpServer.formatSearchText(results))));
        out.put("structuredContent", Map.of("results", results));
        return out;
    }

    private Map<String, Object> getMarkdownCall(JsonNode args, String userId) {
        if (args == null || !args.hasNonNull("doc_id")) {
            throw new IllegalArgumentException("Thiếu tham số bắt buộc 'doc_id'");
        }
        String docId = args.get("doc_id").asText();
        String md = mcp.readMarkdownIfAllowed(userId, docId);
        if (md == null) {
            // Không phân biệt tồn tại vs ngoài phạm vi.
            return notFound();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(textContent(md)));
        return out;
    }

    /** Kết quả tool chuẩn cho không-tìm-thấy/không-có-quyền (isError, thông điệp cố định). */
    private static Map<String, Object> notFound() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("isError", Boolean.TRUE);
        out.put("content", List.of(textContent(McpServer.NOT_FOUND_MESSAGE)));
        return out;
    }

    private static Map<String, Object> textContent(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "text");
        m.put("text", text);
        return m;
    }

    // === resources/read ===

    private Map<String, Object> readResource(JsonNode params, String userId) {
        if (params == null || !params.hasNonNull("uri")) {
            throw new IllegalArgumentException("Thiếu 'uri'");
        }
        String uri = params.get("uri").asText();
        String id = parseDocumentUri(uri);
        if (id == null) {
            // URI không khớp mẫu → coi như không tìm thấy (thông điệp giống hệt tool).
            throw new McpResourceException(McpServer.NOT_FOUND_MESSAGE);
        }
        String md = mcp.readMarkdownIfAllowed(userId, id);
        if (md == null) {
            // Ngoài phạm vi hoặc không tồn tại → lỗi KHÔNG phân biệt.
            throw new McpResourceException(McpServer.NOT_FOUND_MESSAGE);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("uri", uri);
        content.put("mimeType", "text/markdown");
        content.put("text", md);
        List<Object> contents = new ArrayList<>();
        contents.add(content);
        return Map.of("contents", contents);
    }

    /** Lấy {id} từ 'dcs://document/{id}'; null nếu không khớp mẫu. */
    private static String parseDocumentUri(String uri) {
        String prefix = "dcs://document/";
        if (uri == null || !uri.startsWith(prefix)) {
            return null;
        }
        String id = uri.substring(prefix.length());
        return id.isEmpty() ? null : id;
    }

    // === Khung JSON-RPC 2.0 ===

    private static Map<String, Object> jsonRpcResult(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> jsonRpcError(Object id, int code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", err);
        return m;
    }

    /** Ngoài phạm vi / không tồn tại trong resources/read → lỗi (thông điệp cố định). */
    private static final class McpResourceException extends RuntimeException {
        McpResourceException(String message) {
            super(message);
        }
    }
}
