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

    private static DataSource dataSource(String url) {
        // Chấp cả JDBC URL (jdbc:postgresql://...) lẫn URL kiểu postgres://.
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url.startsWith("jdbc:") ? url : "jdbc:" + url);
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
