package com.dqh.dcs.web;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.dqh.dcs.audit.AuditService;
import com.dqh.dcs.org.OrgService;
import com.dqh.dcs.search.SearchFilters;
import com.dqh.dcs.search.SearchHit;
import com.dqh.dcs.search.SearchService;
import com.dqh.dcs.session.SessionUser;

/**
 * web/SearchController — ô tìm gợi ý (htmx) + trang kết quả + lọc đa chiều (port
 * src/routes/search.ts, T-056..058).
 */
@Controller
public class SearchController {

    private final Guards guards;
    private final SearchService search;
    private final OrgService org;
    private final AuditService audit;

    public SearchController(Guards guards, SearchService search, OrgService org, AuditService audit) {
        this.guards = guards;
        this.search = search;
        this.org = org;
        this.audit = audit;
    }

    private static List<String> asArray(List<String> v) {
        return v == null ? List.of() : v;
    }

    private record State(String q, boolean includeHistorical, SearchFilters filters) {
    }

    private State parseState(String q, String historical, List<String> docType, String origin,
            String scopeLevel, String effFrom, String effTo, List<String> matchLevel, String sort,
            String buId, String siteId, String deptId) {
        String qq = q == null ? "" : q.trim();
        boolean includeHistorical = "1".equals(historical);
        SearchFilters filters = new SearchFilters(asArray(docType), origin == null ? "" : origin.trim(),
                scopeLevel == null ? "" : scopeLevel.trim(), effFrom == null ? "" : effFrom.trim(),
                effTo == null ? "" : effTo.trim(), asArray(matchLevel), sort == null ? "" : sort.trim(),
                buId == null ? "" : buId.trim(), siteId == null ? "" : siteId.trim(),
                deptId == null ? "" : deptId.trim());
        return new State(qq, includeHistorical, filters);
    }

    /** Gợi ý nhanh cho ô tìm ở thanh trên — trả HTML mảnh cho htmx. */
    @GetMapping("/search/suggest")
    public String suggest(@RequestParam(required = false) String q, Model model) {
        SessionUser user = guards.requireUser();
        model.addAttribute("hits", search.suggest(user.userId(), q == null ? "" : q));
        return "search-suggest";
    }

    /** Mảnh kết quả cho lọc tức thì (htmx). Không ghi audit. */
    @GetMapping("/search/results")
    public String results(@RequestParam(required = false) String q,
            @RequestParam(required = false) String historical,
            @RequestParam(name = "doc_type", required = false) List<String> docType,
            @RequestParam(required = false) String origin,
            @RequestParam(name = "scope_level", required = false) String scopeLevel,
            @RequestParam(name = "eff_from", required = false) String effFrom,
            @RequestParam(name = "eff_to", required = false) String effTo,
            @RequestParam(name = "match_level", required = false) List<String> matchLevel,
            @RequestParam(required = false) String sort,
            @RequestParam(name = "bu_id", required = false) String buId,
            @RequestParam(name = "site_id", required = false) String siteId,
            @RequestParam(name = "dept_id", required = false) String deptId, Model model) {
        SessionUser user = guards.requireUser();
        State s = parseState(q, historical, docType, origin, scopeLevel, effFrom, effTo, matchLevel, sort,
                buId, siteId, deptId);
        List<SearchHit> hits = s.q().isEmpty() ? List.of()
                : search.search(user.userId(), s.q(), s.includeHistorical(), s.filters(), 100);
        model.addAttribute("q", s.q());
        model.addAttribute("hits", hits);
        model.addAttribute("f", s.filters());
        model.addAttribute("scopeNames", resolveScopeNames(s.filters()));
        return "search-results-list";
    }

    /** Trang kết quả đầy đủ. Công tắc bản lịch sử mặc định tắt (REQ-K06). */
    @GetMapping("/search")
    public String searchPage(@RequestParam(required = false) String q,
            @RequestParam(required = false) String historical,
            @RequestParam(name = "doc_type", required = false) List<String> docType,
            @RequestParam(required = false) String origin,
            @RequestParam(name = "scope_level", required = false) String scopeLevel,
            @RequestParam(name = "eff_from", required = false) String effFrom,
            @RequestParam(name = "eff_to", required = false) String effTo,
            @RequestParam(name = "match_level", required = false) List<String> matchLevel,
            @RequestParam(required = false) String sort,
            @RequestParam(name = "bu_id", required = false) String buId,
            @RequestParam(name = "site_id", required = false) String siteId,
            @RequestParam(name = "dept_id", required = false) String deptId, Model model) {
        SessionUser user = guards.requireUser();
        State s = parseState(q, historical, docType, origin, scopeLevel, effFrom, effTo, matchLevel, sort,
                buId, siteId, deptId);
        List<SearchHit> hits = s.q().isEmpty() ? List.of()
                : search.search(user.userId(), s.q(), s.includeHistorical(), s.filters(), 100);
        if (!s.q().isEmpty()) {
            search.auditSearch(audit, user.userId(), s.q(), s.includeHistorical(), s.filters(), hits.size());
        }
        model.addAttribute("q", s.q());
        model.addAttribute("hits", hits);
        model.addAttribute("includeHistorical", s.includeHistorical());
        model.addAttribute("f", s.filters());
        model.addAttribute("bus", org.listBu());
        model.addAttribute("sites", org.listSites());
        model.addAttribute("depts", org.listDepartments());
        model.addAttribute("scopeNames", resolveScopeNames(s.filters()));
        return "search-results";
    }

    private ScopeNames resolveScopeNames(SearchFilters f) {
        String bu = null;
        String site = null;
        String dept = null;
        boolean any = notEmpty(f.buId()) || notEmpty(f.siteId()) || notEmpty(f.deptId());
        if (any) {
            if (notEmpty(f.buId())) {
                bu = org.listBu().stream().filter(b -> b.buId().equals(f.buId()))
                        .map(b -> b.buCode() + " — " + b.name()).findFirst().orElse(null);
            }
            if (notEmpty(f.siteId())) {
                site = org.listSites().stream().filter(s -> s.siteId().equals(f.siteId()))
                        .map(s -> s.siteCode() + " — " + s.name()).findFirst().orElse(null);
            }
            if (notEmpty(f.deptId())) {
                dept = org.listDepartments().stream().filter(d -> d.deptId().equals(f.deptId()))
                        .map(d -> d.deptCode() + " — " + d.name()).findFirst().orElse(null);
            }
        }
        return new ScopeNames(bu, site, dept);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    public record ScopeNames(String bu, String site, String dept) {
        public String getBu() {
            return bu;
        }

        public String getSite() {
            return site;
        }

        public String getDept() {
            return dept;
        }
    }
}
