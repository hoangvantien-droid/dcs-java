package com.dqh.dcs.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.dqh.dcs.escalation.EscalationService;
import com.dqh.dcs.jobs.DailyJobsRunner.DailyJobsResult;
import com.dqh.dcs.lifecycle.LifecycleService;
import com.dqh.dcs.session.SessionService;

/**
 * jobs/JobsRunnerTest — FEAT-004 verification.
 *
 * Với DB sẵn (env DATABASE_URL_TEST) thì chạy chuỗi tác vụ nền runDailyJobs()
 * và khẳng định KHÔNG ném lỗi (các fn_* Postgres thực thi trọn vẹn). Không có DB
 * thì SELF-SKIP bằng Assumptions (giống GĐ1) — Testcontainers wiring thuộc FEAT-005.
 *
 * Gọi thẳng runDailyJobs() thay vì CommandLineRunner.run() để tránh
 * SpringApplication.exit (System.exit) kết thúc tiến trình test.
 */
class JobsRunnerTest {

    private static String testDbUrl() {
        String url = System.getenv("DATABASE_URL_TEST");
        if (url == null || url.isBlank()) {
            url = System.getProperty("DATABASE_URL_TEST");
        }
        return url == null || url.isBlank() ? null : url;
    }

    private static DataSource dataSource(String raw) {
        // Chấp cả JDBC URL (jdbc:postgresql://...) lẫn URL kiểu Node (postgres://user:pw@host:port/db).
        // Scheme JDBC Postgres là 'jdbc:postgresql:' — KHÔNG phải 'jdbc:postgres:' — nên URL kiểu
        // 'postgres://...' phải được dựng lại qua java.net.URI (giống DbSupport.fromJdbcUrl), chứ
        // không chỉ ghép tiền tố 'jdbc:'.
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
        // stringtype=unspecified: Postgres tự suy kiểu đích (uuid/date/enum...) cho param String,
        // nhất quán với DbSupport/application.yml.
        if (!jdbcUrl.contains("stringtype=")) {
            jdbcUrl += (jdbcUrl.contains("?") ? "&" : "?") + "stringtype=unspecified";
        }
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(jdbcUrl);
        if (user != null) {
            ds.setUsername(user);
            ds.setPassword(pw);
        }
        return ds;
    }

    @Test
    void runDailyJobsWithDbDoesNotThrow() {
        String url = testDbUrl();
        assumeTrue(url != null,
                "Bỏ qua: thiếu DATABASE_URL_TEST (Testcontainers DB thuộc FEAT-005)");

        DataSource ds = dataSource(url);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);

        LifecycleService lifecycle = new LifecycleService(jdbc);
        EscalationService escalation = new EscalationService(jdbc);
        SessionService sessions = new SessionService(jdbc);
        // ApplicationContext không dùng ở runDailyJobs() nên truyền null là đủ.
        ApplicationContext ctx = null;
        DailyJobsRunner runner = new DailyJobsRunner(ctx, lifecycle, escalation, sessions);

        final DailyJobsResult[] out = new DailyJobsResult[1];
        assertThatCode(() -> out[0] = runner.runDailyJobs()).doesNotThrowAnyException();
        // Các bộ đếm là số đã xử lý (>= 0 trên DB sạch).
        assertThat(out[0]).isNotNull();
        assertThat(out[0].activated()).isGreaterThanOrEqualTo(0);
        assertThat(out[0].reviewsGenerated()).isGreaterThanOrEqualTo(0);
        assertThat(out[0].escalationsSent()).isGreaterThanOrEqualTo(0);
        assertThat(out[0].sessionsPurged()).isGreaterThanOrEqualTo(0);
    }
}
