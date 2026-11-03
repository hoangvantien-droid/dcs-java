package com.dqh.dcs.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dqh.dcs.mcp.McpServer.SearchResultItem;
import com.dqh.dcs.search.SearchHit;

/**
 * mcp/McpServerTest — unit cho các hàm thuần của máy chủ MCP (không cần DB):
 * clampLimit, capSnippet, mapSearchHits. Port tinh thần test bản TS.
 */
class McpServerTest {

    @Test
    void clampLimitMacDinh20KhiNull() {
        assertEquals(20, McpServer.clampLimit(null));
    }

    @Test
    void clampLimitDuoi1Thanh1() {
        assertEquals(1, McpServer.clampLimit(0));
        assertEquals(1, McpServer.clampLimit(-5));
    }

    @Test
    void clampLimitVuotTranThanh50() {
        assertEquals(50, McpServer.clampLimit(51));
        assertEquals(50, McpServer.clampLimit(1000));
    }

    @Test
    void clampLimitGiuNguyenTrongPhamVi() {
        assertEquals(1, McpServer.clampLimit(1));
        assertEquals(20, McpServer.clampLimit(20));
        assertEquals(50, McpServer.clampLimit(50));
    }

    @Test
    void capSnippetNullThanhRong() {
        assertEquals("", McpServer.capSnippet(null));
    }

    @Test
    void capSnippetNganGiuNguyen() {
        assertEquals("abc", McpServer.capSnippet("abc"));
    }

    @Test
    void capSnippetDaiCat300() {
        String s = "x".repeat(500);
        String out = McpServer.capSnippet(s);
        assertEquals(300, out.length());
        assertTrue(s.startsWith(out));
    }

    @Test
    void capSnippetDungDo300GiuNguyen() {
        String s = "y".repeat(300);
        assertEquals(300, McpServer.capSnippet(s).length());
    }

    @Test
    void mapSearchHitsGiuMatchLevelVaCatSnippet() {
        SearchHit hit = new SearchHit(
                "QT-001", "11111111-1111-1111-1111-111111111111", "1", "Quy trình", "hieu_luc",
                "1.2", "Mục con", "z".repeat(400), "section", 0.87,
                "dept", "2024-01-01", "2025-01-01", "d1", "QA", "noi_bo");
        List<SearchResultItem> out = McpServer.mapSearchHits(List.of(hit));
        assertEquals(1, out.size());
        SearchResultItem r = out.get(0);
        assertEquals("QT-001", r.doc_id());
        assertEquals("section", r.match_level());
        assertEquals(300, r.snippet().length()); // snippet đã cắt
        assertEquals(0.87, r.rank());
    }

    @Test
    void mapSearchHitsRongTraRong() {
        assertTrue(McpServer.mapSearchHits(List.of()).isEmpty());
        assertEquals("Không có kết quả nào khớp.", McpServer.formatSearchText(List.of()));
    }
}
