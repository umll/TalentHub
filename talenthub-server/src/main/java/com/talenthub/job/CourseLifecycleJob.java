package com.talenthub.job;

import com.talenthub.mapper.CourseMapper;
import com.talenthub.model.entity.Course;
import com.talenthub.service.CourseService;
import com.talenthub.service.StockCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 课程生命周期调度：到点开抢/截止 + 库存预热（业务设计 §6）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseLifecycleJob {

    private final CourseMapper courseMapper;
    private final CourseService courseService;
    private final StockCacheService stockCacheService;

    @Scheduled(fixedDelay = 5000)
    public void tick() {
        int opened = courseMapper.openDueCourses();
        int closed = courseMapper.closeDueCourses();
        if (opened > 0 || closed > 0) {
            log.info("课程状态翻转: 开抢 {} 门, 截止 {} 门", opened, closed);
        }
        // 开抢前 5 分钟内的课程预热；已开抢但缺 key 的补预热（预热失败兜底）
        for (Course course : courseMapper.selectPreheatCandidates()) {
            ensurePreheated(course, false);
        }
        for (Course course : courseMapper.selectByStatus(Course.STATUS_OPEN)) {
            ensurePreheated(course, true);
        }
    }

    private void ensurePreheated(Course course, boolean alreadyOpen) {
        if (stockCacheService.isPreheated(course.getId())) {
            return;
        }
        if (alreadyOpen) {
            log.warn("[预热告警] 课程 {} 已开抢但库存未预热，立即补预热", course.getId());
        }
        try {
            courseService.preheat(course.getId());
        } catch (Exception e) {
            log.error("[预热告警] 课程 {} 预热失败，下一轮重试", course.getId(), e);
        }
    }
}
