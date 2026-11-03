package com.dqh.dcs.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * support/DbSupport — hạ tầng DÙNG CHUNG cho test tích hợp chạm DB (QĐ-K, tái
 * hiện khuôn self-skip của GĐ1 trong test/domain/_lifecycle-db.ts).
 *
 * Thứ tự nối: env DATABASE_URL_TEST → DATABASE_URL → Testcontainers Postgres
 * (image pgvector/pgvector:pg16) nạp tuần tự 28 .sql nguồn (copy verbatim ở
 * src/test/resources/db/migrations) + seed tối thiểu. Nếu KHÔNG có Docker/DB
 * thì dbOk=false — từng test gọi Assumptions.assumeTrue(DB.dbOk()) để tự skip,
 * `mvn test` ở máy không-DB vẫn xanh (KHÔNG fail build vì thiếu DB).
 *
 * Singleton tải chậm (một container/connection dùng chung mọi IT), dọn khi JVM
 * tắt. Khởi tạo container được bọc try/catch: thiếu Docker không làm vỡ lớp.
 */
public final class DbSupport {

    /** Seed tham chiếu theo scripts/seed_matrix.mjs: author@mml.io + dc_* theo site, ≥2 site. */
    public static final class Seed {
        public final String authorId;
        public final List<String> siteIds;   // ≥2 site, theo site_code
        public final String dcSite0UserId;    // DC_SITE_DOC_CONTROL của siteIds[0]
        public final String dcSite1UserId;    // DC_SITE_DOC_CONTROL của siteIds[1]
        public final String readerId;
        public final String adminId;

        Seed(String authorId, List<String> siteIds, String dcSite0UserId, String dcSite1UserId,
                String readerId, String adminId) {
            this.authorId = authorId;
            this.siteIds = siteIds;
            this.dcSite0UserId = dcSite0UserId;
            this.dcSite1UserId = dcSite1UserId;
            this.readerId = readerId;
            this.adminId = adminId;
        }
    }

    private static boolean initialized;
    private static boolean dbOk;
    private static DataSource dataSource;
    private static NamedParameterJdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static ObjectMapper objectMapper;
    private static Seed seed;
    private static Object container; // org.testcontainers...PostgreSQLContainer (qua reflection để tránh cứng phụ thuộc)

    private DbSupport() {
    }

    public static synchronized boolean dbOk() {
        ensureInit();
        return dbOk;
    }

    public static NamedParameterJdbcTemplate jdbc() {
        ensureInit();
        return jdbc;
    }

    public static TransactionTemplate tx() {
        ensureInit();
        return tx;
    }

    public static ObjectMapper objectMapper() {
        ensureInit();
        return objectMapper;
    }

    /** JdbcTemplate thuần trên cùng DataSource (tiện cho assert/insert trong IT). */
    public static JdbcTemplate plainJdbc() {
        ensureInit();
        return new JdbcTemplate(dataSource);
    }

    public static Seed seed() {
        ensureInit();
        return seed;
    }

    private static synchronized void ensureInit() {
        if (initialized) {
            return;
        }
        initialized = true;
        objectMapper = new ObjectMapper();
        String url = System.getenv("DATABASE_URL_TEST");
        if (url == null || url.isBlank()) {
            url = System.getenv("DATABASE_URL");
        }
        try {
            if (url != null && !url.isBlank()) {
                dataSource = fromJdbcUrl(url);
            } else {
                dataSource = startContainer();
            }
            if (dataSource == null) {
                dbOk = false;
                return;
            }
            jdbc = new NamedParameterJdbcTemplate(dataSource);
            tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            // Probe nhanh.
            new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class);
            runMigrations();
            seed = seedMatrix();
            dbOk = true;
        } catch (Throwable t) {
            // Thiếu Docker / không nối được DB → self-skip, KHÔNG fail build.
            dbOk = false;
        }
    }

    /** Chuyển DATABASE_URL (postgres://user:pw@host:port/db) sang DataSource JDBC. */
    private static DataSource fromJdbcUrl(String raw) {
        String jdbcUrl;
        String user = null;
        String pw = null;
        if (raw.startsWith("jdbc:")) {
            jdbcUrl = raw;
        } else {
            java.net.URI uri = java.net.URI.create(raw);
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                user = parts[0];
                pw = parts.length > 1 ? parts[1] : "";
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String db = uri.getPath() == null ? "" : uri.getPath();
            jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + db;
        }
        jdbcUrl = withStringtypeUnspecified(jdbcUrl);
        DriverManagerDataSource ds = new DriverManagerDataSource(jdbcUrl);
        ds.setDriverClassName("org.postgresql.Driver");
        if (user != null) {
            ds.setUsername(user);
            ds.setPassword(pw);
        }
        return ds;
    }

    /**
     * Thêm tham số driver `stringtype=unspecified` vào JDBC URL để Postgres tự
     * suy kiểu đích (uuid/date/enum...) cho param String — giống driver `postgres`
     * của bản Node, tránh lỗi 'operator does not exist: uuid = character varying'.
     * Giữ nguyên nếu URL đã khai stringtype.
     */
    private static String withStringtypeUnspecified(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.contains("stringtype=")) {
            return jdbcUrl;
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "stringtype=unspecified";
    }

    /** Khởi Testcontainers PostgreSQLContainer('pgvector/pgvector:pg16'). */
    private static DataSource startContainer() {
        org.testcontainers.containers.PostgreSQLContainer<?> pg =
                new org.testcontainers.containers.PostgreSQLContainer<>(
                        org.testcontainers.utility.DockerImageName
                                .parse("pgvector/pgvector:pg16")
                                .asCompatibleSubstituteFor("postgres"));
        pg.start();
        container = pg;
        Runtime.getRuntime().addShutdownHook(new Thread(pg::stop));
        DriverManagerDataSource ds = new DriverManagerDataSource(withStringtypeUnspecified(pg.getJdbcUrl()));
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUsername(pg.getUsername());
        ds.setPassword(pg.getPassword());
        return ds;
    }

    /** Nạp tuần tự 28 .sql nguồn từ classpath db/migrations (mỗi file một giao dịch). */
    private static void runMigrations() throws Exception {
        JdbcTemplate plain = new JdbcTemplate(dataSource);
        for (int i = 1; i <= 28; i++) {
            String name = migrationName(i);
            String sql = readResource("db/migrations/" + name);
            tx().execute(status -> {
                plain.execute(sql);
                return null;
            });
        }
    }

    private static final String[] MIGRATIONS = {
            "001_schema.sql", "002_phu_luc_giai_doan_1.sql", "003_auth_giai_doan_1.sql",
            "004_fix_escalation_targets.sql", "005_ver2_organization_authorization.sql",
            "006_fix_verify_token_length.sql", "007_draft_mien_kiem_truoc_khi_nop.sql",
            "008_verify_token_urlsafe.sql", "009_dept_code_unique_per_site.sql",
            "010_drop_approval_fields.sql", "011_site_rollout.sql", "012_roles_3vai.sql",
            "013_roles_3vai_map.sql", "014_drop_legacy_columns.sql", "015_add_primary_dept.sql",
            "016_fulltext_markdown.sql", "017_receipt_model.sql", "018_role_site_doc_control.sql",
            "019_audit_events_receipt.sql", "020_search_current_da_ban_hanh.sql",
            "021_deactivate_legacy_release_roles.sql", "022_audit_event_thu_hoi_ban_in.sql",
            "023_search_filters.sql", "024_search_fix_origin.sql", "025_search_scope_ids.sql",
            "026_lifecycle_sync.sql", "027_report_content_error_after_accept.sql",
            "028_site_effective_date.sql"
    };

    private static String migrationName(int i) {
        return MIGRATIONS[i - 1];
    }

    private static String readResource(String path) throws Exception {
        var in = DbSupport.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            // dự phòng đọc từ cây nguồn test khi chạy ngoài classpath chuẩn
            Path p = Path.of("src/test/resources", path);
            return Files.readString(p, StandardCharsets.UTF_8);
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Seed tối thiểu (port scripts/seed_matrix.mjs, rút gọn): BU MEAT, 2 site
     * (MML, HCM) + dept, và các account author / reader / admin / dc_mml / dc_hcm.
     * password_hash là hash argon2id tĩnh (không dùng cho test này). ≥2 site để
     * kiểm sai khác giữa site.
     */
    private static Seed seedMatrix() {
        JdbcTemplate db = new JdbcTemplate(dataSource);
        return tx().execute(status -> {
            String buId = UUID.randomUUID().toString();
            db.update("INSERT INTO org_bu (bu_id, bu_code, name) VALUES (?::uuid, 'MEAT', 'MeatLife')", buId);

            List<String> sites = new ArrayList<>();
            String mml = insertSite(db, "MML", "MML Site", buId);
            String hcm = insertSite(db, "HCM", "HCM Site", buId);
            sites.add(mml);
            sites.add(hcm);

            String qaMml = insertDept(db, "QA", "QA MML", mml);
            String qaHcm = insertDept(db, "QA2", "QA HCM", hcm);

            String authorId = insertUser(db, "author@mml.io", "Author User", qaMml);
            grantRole(db, authorId, "DC_AUTHOR", "group", null, null, null);

            String readerId = insertUser(db, "reader@mml.io", "Reader User", qaMml);
            grantRole(db, readerId, "DC_READER", "group", null, null, null);

            String adminId = insertUser(db, "admin@mml.io", "Admin User", null);
            grantRole(db, adminId, "DC_ADMIN", "group", null, null, null);

            String dcMml = insertUser(db, "dc_mml@mml.io", "DC Site MML", qaMml);
            grantRole(db, dcMml, "DC_SITE_DOC_CONTROL", "site", null, mml, null);

            String dcHcm = insertUser(db, "dc_hcm@mml.io", "DC Site HCM", qaHcm);
            grantRole(db, dcHcm, "DC_SITE_DOC_CONTROL", "site", null, hcm, null);

            // Bảo đảm thứ tự site theo site_code (HCM < MML) như truy vấn test.
            sites.sort((a, b) -> siteCode(db, a).compareTo(siteCode(db, b)));
            String dc0 = siteCode(db, sites.get(0)).equals("HCM") ? dcHcm : dcMml;
            String dc1 = siteCode(db, sites.get(1)).equals("MML") ? dcMml : dcHcm;
            return new Seed(authorId, sites, dc0, dc1, readerId, adminId);
        });
    }

    private static String siteCode(JdbcTemplate db, String siteId) {
        return db.queryForObject("SELECT site_code FROM org_site WHERE site_id = ?::uuid",
                String.class, siteId);
    }

    private static String insertSite(JdbcTemplate db, String code, String name, String buId) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO org_site (site_id, site_code, name, legacy_primary_bu_id) "
                + "VALUES (?::uuid, ?, ?, ?::uuid)", id, code, name, buId);
        db.update("INSERT INTO org_site_bu (site_id, bu_id) VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING",
                id, buId);
        return id;
    }

    private static String insertDept(JdbcTemplate db, String code, String name, String siteId) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO org_department (dept_id, dept_code, name, site_id) "
                + "VALUES (?::uuid, ?, ?, ?::uuid)", id, code, name, siteId);
        return id;
    }

    /** Hash argon2id tĩnh (không dùng để đăng nhập trong các IT này). */
    private static final String STATIC_HASH =
            "$argon2id$v=19$m=65536,t=3,p=4$4k8gvr/5OOlb6w5+OziaIA$9LURYT9oGMaZXLtmjw4JQle28BQMYM4HqGJkbr0bqrs";

    private static String insertUser(JdbcTemplate db, String email, String fullName, String primaryDept) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO users (user_id, email, full_name, password_hash, is_active, temp_record, primary_dept_id) "
                + "VALUES (?::uuid, ?, ?, ?, TRUE, FALSE, ?::uuid)",
                id, email, fullName, STATIC_HASH, primaryDept);
        if (primaryDept != null) {
            db.update("INSERT INTO user_departments (user_id, dept_id) VALUES (?::uuid, ?::uuid) "
                    + "ON CONFLICT DO NOTHING", id, primaryDept);
        }
        return id;
    }

    private static void grantRole(JdbcTemplate db, String userId, String roleCode, String scopeLevel,
            String buId, String siteId, String deptId) {
        db.update("INSERT INTO user_roles (user_id, role_code, scope_level, scope_bu_id, scope_site_id, scope_dept_id, is_active) "
                + "VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?::uuid, TRUE) "
                + "ON CONFLICT (user_id, role_code, scope_level, scope_bu_id, scope_site_id, scope_dept_id) "
                + "DO UPDATE SET is_active = TRUE",
                userId, roleCode, scopeLevel, buId, siteId, deptId);
    }

    /** Dọn một tài liệu test (receipt cascade qua version). */
    public static void cleanupDoc(String docId) {
        tx().execute(status -> {
            JdbcTemplate db = new JdbcTemplate(dataSource);
            db.update("DELETE FROM document_versions WHERE doc_id = ?", docId);
            db.update("DELETE FROM documents WHERE doc_id = ?", docId);
            return null;
        });
    }
}
