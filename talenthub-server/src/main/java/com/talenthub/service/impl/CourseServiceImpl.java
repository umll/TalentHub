package com.talenthub.service.impl;

import com.talenthub.common.BizException;
import com.talenthub.common.ResultCode;
import com.talenthub.mapper.CourseMapper;
import com.talenthub.mapper.EnrollmentMapper;
import com.talenthub.mapper.ReconcileLogMapper;
import com.talenthub.model.dto.CourseSaveDTO;
import com.talenthub.model.entity.Course;
import com.talenthub.model.vo.CourseVO;
import com.talenthub.model.vo.ReconcileLogVO;
import com.talenthub.service.CourseService;
import com.talenthub.service.StockCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourseServiceImpl implements CourseService {

    private static final int RECONCILE_LOG_LIMIT = 50;
    /** Redis key 过期 = 报名截止 + 7 天（业务设计 §3.1） */
    private static final int REDIS_EXPIRE_DAYS_AFTER_END = 7;

    private final CourseMapper courseMapper;
    private final EnrollmentMapper enrollmentMapper;
    private final ReconcileLogMapper reconcileLogMapper;
    private final StockCacheService stockCacheService;

    @Override
    public List<CourseVO> list(long userId) {
        List<CourseVO> courses = courseMapper.selectAllWithEnrolled(userId);
        courses.forEach(this::overrideStockFromRedis);
        return courses;
    }

    @Override
    public CourseVO detail(long courseId, long userId) {
        CourseVO course = courseMapper.selectVoById(courseId, userId);
        if (course == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        overrideStockFromRedis(course);
        return course;
    }

    /** 报名中的课程对外展示 Redis 实时库存 */
    private void overrideStockFromRedis(CourseVO course) {
        if (course.getStatus() != Course.STATUS_OPEN) {
            return;
        }
        Long redisStock = stockCacheService.getStock(course.getId());
        if (redisStock != null) {
            course.setStock(redisStock.intValue());
        }
    }

    @Override
    public long create(CourseSaveDTO dto) {
        validateTimeRange(dto);
        Course course = new Course();
        course.setTitle(dto.getTitle());
        course.setTotalQuota(dto.getTotalQuota());
        course.setStock(dto.getTotalQuota());
        course.setEnrollStart(dto.getEnrollStart());
        course.setEnrollEnd(dto.getEnrollEnd());
        course.setStatus(Course.STATUS_NOT_STARTED);
        courseMapper.insert(course);
        // 开抢时间临近的课程立即预热，状态翻转交给调度任务
        if (dto.getEnrollStart().isBefore(OffsetDateTime.now().plusMinutes(5))) {
            preheat(course.getId());
        }
        return course.getId();
    }

    @Override
    public void update(long courseId, CourseSaveDTO dto) {
        validateTimeRange(dto);
        if (courseMapper.updateEditable(courseId, dto) == 0) {
            throw new BizException(ResultCode.COURSE_NOT_EDITABLE);
        }
        // 名额修改走统一入口同步刷 Redis（业务设计 §6），仅当已预热过才需要刷新
        if (stockCacheService.isPreheated(courseId)) {
            preheat(courseId);
        }
    }

    @Override
    public void preheat(long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        if (course.getStatus() == Course.STATUS_ENDED || course.getStatus() == Course.STATUS_CANCELED) {
            throw new BizException(ResultCode.ENDED);
        }
        List<Long> enrolledUserIds = enrollmentMapper.selectActiveUserIds(courseId);
        stockCacheService.preheat(courseId, course.getStock(), enrolledUserIds,
                course.getEnrollEnd().plusDays(REDIS_EXPIRE_DAYS_AFTER_END));
        log.info("课程 {} 预热完成: stock={}, enrolledUsers={}", courseId, course.getStock(), enrolledUserIds.size());
    }

    @Override
    public List<ReconcileLogVO> recentReconcileLogs() {
        return reconcileLogMapper.selectRecent(RECONCILE_LOG_LIMIT);
    }

    private void validateTimeRange(CourseSaveDTO dto) {
        if (!dto.getEnrollStart().isBefore(dto.getEnrollEnd())) {
            throw new BizException(ResultCode.BAD_REQUEST);
        }
    }
}
