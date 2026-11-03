package com.dqh.dcs.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.SpringApplication;
import org.springframework.stereotype.Component;

import com.dqh.dcs.escalation.EscalationService;
import com.dqh.dcs.lifecycle.LifecycleService;
import com.dqh.dcs.session.SessionService;

/**
 * jobs/DailyJobsRunner — tác vụ nền chạy hằng ngày (QĐ-H, port src/jobs/scheduler.ts, T-035).
 *
 * Kích hoạt bởi profile 'jobs': chạy như tiến trình ONESHOT qua systemd timer
 * (deploy/dcs-jobs.timer), KHÔNG chạy trong tiến trình máy chủ web. KHÔNG dùng
 * {@code @Scheduled}. Trên máy 1 GB, tách tiến trình để trả sạch bộ nhớ sau khi
 * xong (DCS-8 §1.2): chạy xong gọi {@link SpringApplication#exit} rồi thoát.
 *
 * Gồm, chạy TUẦN TỰ: chuyển hiệu lực theo ngày (fn_activate_due_versions) → sinh
 * rà soát tới hạn (fn_generate_due_reviews) → leo thang (fn_run_escalations) →
 * dọn phiên hết hạn (purgeExpiredSessions).
 */
@Component
@Profile("jobs")
public class DailyJobsRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DailyJobsRunner.class);

    private final ApplicationContext ctx;
    private final LifecycleService lifecycle;
    private final EscalationService escalation;
    private final SessionService sessions;

    public DailyJobsRunner(ApplicationContext ctx, LifecycleService lifecycle,
            EscalationService escalation, SessionService sessions) {
        this.ctx = ctx;
        this.lifecycle = lifecycle;
        this.escalation = escalation;
        this.sessions = sessions;
    }

    /**
     * Chạy chuỗi tác vụ nền hằng ngày. Trả về bản tóm tắt số lượng xử lý. Tách
     * khỏi run() để test gọi trực tiếp mà không kích hoạt SpringApplication.exit.
     */
    public DailyJobsResult runDailyJobs() {
        int activated = lifecycle.activateDueVersions();
        int reviewsGenerated = lifecycle.generateDueReviews();
        int escalationsSent = escalation.runEscalations();
        int sessionsPurged = sessions.purgeExpiredSessions();
        return new DailyJobsResult(activated, reviewsGenerated, escalationsSent, sessionsPurged);
    }

    @Override
    public void run(String... args) {
        int code = 0;
        try {
            DailyJobsResult r = runDailyJobs();
            log.info("Tác vụ nền xong: kích hoạt {}, sinh rà soát {}, leo thang {}, dọn phiên {}",
                    r.activated(), r.reviewsGenerated(), r.escalationsSent(), r.sessionsPurged());
        } catch (RuntimeException err) {
            log.error("Tác vụ nền thất bại", err);
            code = 1;
        }
        // Trả sạch bộ nhớ: thoát tiến trình oneshot sau khi xong (DCS-8 §1.2).
        final int exitCode = code;
        System.exit(SpringApplication.exit(ctx, () -> exitCode));
    }

    /** Kết quả chuỗi tác vụ nền (tái hiện object trả về của runDailyJobs TS). */
    public record DailyJobsResult(int activated, int reviewsGenerated,
            int escalationsSent, int sessionsPurged) {
    }
}
