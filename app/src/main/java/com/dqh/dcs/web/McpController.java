package com.dqh.dcs.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * web/McpController — placeholder cho /mcp (port src/routes/mcp.ts).
 *
 * ĐIỂM MỞ GĐ2 (DCS-7 §3 / QĐ-J / Lệch#2): máy chủ MCP Streamable HTTP đầy đủ để
 * lại cho FEAT-004. Ở đây chỉ giữ chỗ để không chặn build và phản hồi rõ ràng:
 *  - POST /mcp → 501 Not Implemented (chưa dựng MCP server).
 *  - GET  /mcp → 405 Method Not Allowed (transport stateless không dùng SSE).
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    @PostMapping
    public ResponseEntity<Map<String, String>> post() {
        return ResponseEntity.status(501).body(Map.of("error", "not_implemented",
                "note", "MCP server để lại Điểm mở GĐ2 (FEAT-004)"));
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> get() {
        return ResponseEntity.status(405).body(Map.of("error", "method_not_allowed"));
    }
}
