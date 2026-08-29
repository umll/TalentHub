package com.talenthub.job;

import com.talenthub.mapper.CourseMapper;
import com.talenthub.mapper.EnrollmentMapper;
import com.talenthub.mapper.ReconcileLogMapper;
import com.talenthub.model.entity.Course;
import com.talenthub.model.entity.ReconcileLog;
import com.talenthub.service.StockCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库存对账补偿（业务设计 §5）：纯兜底，正常情况下每轮应发现 0 差异。
 * 以 DB 为准、单向修正：虚高立即修（防止多余请求打 DB），虚低连续 N 轮才修（容忍在途请求）。
 */
@Slf4j
@Component
public class StockReconcileJob {

    private static final int REDIS_EXPIRE_DAYS_AFTER_END = 7;

    private final CourseMapper courseMapper;
    private final EnrollmentMapper enrollmentMapper;
    private final ReconcileLogMapper reconcileLogMapper;
    private final StockCacheService stockCacheService;
    private final int lowRoundsThreshold;

    /** courseId → Redis 虚低连续轮数 */
    private final Map<Long, Integer> lowRounds = new ConcurrentHashMap<>();

    public StockReconcileJob(CourseMapper courseMapper,
                             EnrollmentMapper enrollmentMapper,
                             ReconcileLogMapper reconcileLogMapper,
                             StockCacheService stockCacheService,
                             @Value("${talenthub.reconcile.low-rounds-threshold}") int lowRoundsThreshold) {
        this.courseMapper = courseMapper;
        this.enrollmentMapper = enrollmentMapper;
        this.reconcileLogMapper = reconcileLogMapper;
        this.stockCacheService = stockCacheService;
        this.lowRoundsThreshold = lowRoundsThreshold;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void reconcile() {
        for (Course course : courseMapper.selectByStatus(Course.STATUS_OPEN)) {
            try {
                reconcileOne(course);
            } catch (Exception e) {
                log.error("[对账告警] 课程 {} 对账异常", course.getId(), e);
            }
        }
    }

    private void reconcileOne(Course course) {
        long courseId = course.getId();
        int dbStock = course.getStock();

        // 交叉验证 DB 自身：stock 必须等于 总名额 - 有效报名数，否则 DB 有 bug，停止修正（业务设计 §5）
        int activeCount = enrollmentMapper.countActive(courseId);
        if (dbStock != course.getTotalQuota() - activeCount) {
            log.error("[对账告警] 课程 {} DB 自身不一致: stock={}, totalQuota={}, activeCount={}",
                    courseId, dbStock, course.getTotalQuota(), activeCount);
            saveLog(courseId, null, dbStock, "DB_SELF_INCONSISTENT");
            return;
        }

        Long redisStock = stockCacheService.getStock(courseId);
        if (redisStock == null) {
            return;    // 未预热，交给生命周期任务
        }
        if (redisStock == dbStock) {
            lowRounds.remove(courseId);
            return;
        }

        if (redisStock > dbStock) {
            // Redis 虚高：立即修正，最坏效果只是多几个请求被 DB 的 stock>0 拦住
            log.warn("[对账修正] 课程 {} Redis 虚高: redis={}, db={}", courseId, redisStock, dbStock);
            rebuildFromDb(course, dbStock);
            saveLog(courseId, redisStock, dbStock, "FIX_REDIS_HIGH");
            lowRounds.remove(courseId);
            return;
        }

        // Redis 虚低：可能是在途请求，连续 N 轮仍存在才修正
        int rounds = lowRounds.merge(courseId, 1, Integer::sum);
        if (rounds < lowRoundsThreshold) {
            log.info("[对账观察] 课程 {} Redis 虚低(第 {} 轮): redis={}, db={}", courseId, rounds, redisStock, dbStock);
            return;
        }
        log.warn("[对账修正] 课程 {} Redis 虚低持续 {} 轮: redis={}, db={}", courseId, rounds, redisStock, dbStock);
        rebuildFromDb(course, dbStock);
        saveLog(courseId, redisStock, dbStock, "FIX_REDIS_LOW");
        lowRounds.remove(courseId);
    }

    private void rebuildFromDb(Course course, int dbStock) {
        stockCacheService.preheat(course.getId(), dbStock,
                enrollmentMapper.selectActiveUserIds(course.getId()),
                course.getEnrollEnd().plusDays(REDIS_EXPIRE_DAYS_AFTER_END));
    }

    private void saveLog(long courseId, Long redisStock, int dbStock, String action) {
        ReconcileLog reconcileLog = new ReconcileLog();
        reconcileLog.setCourseId(courseId);
        reconcileLog.setRedisStock(redisStock);
        reconcileLog.setDbStock(dbStock);
        reconcileLog.setAction(action);
        reconcileLogMapper.insert(reconcileLog);
    }
}
