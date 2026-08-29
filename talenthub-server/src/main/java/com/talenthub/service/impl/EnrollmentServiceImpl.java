package com.talenthub.service.impl;

import com.talenthub.common.BizException;
import com.talenthub.common.ResultCode;
import com.talenthub.mapper.CourseMapper;
import com.talenthub.mapper.EnrollmentMapper;
import com.talenthub.model.entity.Course;
import com.talenthub.model.vo.EnrollResultVO;
import com.talenthub.model.vo.EnrollmentVO;
import com.talenthub.service.EnrollmentService;
import com.talenthub.service.RateLimitService;
import com.talenthub.service.StockCacheService;
import com.talenthub.service.StockCacheService.PreDeductResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnrollmentServiceImpl implements EnrollmentService {

    private final RateLimitService rateLimitService;
    private final StockCacheService stockCacheService;
    private final EnrollmentMapper enrollmentMapper;
    private final CourseMapper courseMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public EnrollResultVO enroll(long userId, long courseId) {
        rateLimitService.checkCourseLimit(courseId);
        rateLimitService.checkUserLimit(userId, courseId);
        checkEnrollWindow(courseId);

        PreDeductResult preDeduct = stockCacheService.preDeduct(courseId, userId);
        switch (preDeduct) {
            case DUPLICATE -> {
                return EnrollResultVO.alreadyEnrolledResult();
            }
            case SOLD_OUT -> throw new BizException(ResultCode.SOLD_OUT);
            case NOT_PREHEATED -> {
                log.warn("[预热告警] 课程 {} 库存未预热，拒绝报名", courseId);
                throw new BizException(ResultCode.NOT_PREHEATED);
            }
            case PRE_DEDUCTED -> {
                // 预扣成功，进入 DB 事务；此后任何未确认成功的路径必须同步回补（业务设计 §4.3）
            }
        }

        try {
            transactionTemplate.executeWithoutResult(tx -> {
                // 顺序必须先 upsert 后扣库存：热点行锁最后获取、随提交即释放（业务设计 §2.2）
                if (enrollmentMapper.upsertEnrollment(userId, courseId) == 0) {
                    throw new BizException(ResultCode.ALREADY_ENROLLED);
                }
                if (courseMapper.deductStock(courseId) == 0) {
                    throw new BizException(ResultCode.SOLD_OUT);
                }
            });
            return EnrollResultVO.success();
        } catch (BizException e) {
            return handleBizFailure(userId, courseId, e);
        } catch (Exception e) {
            return handleUnknownFailure(userId, courseId, e);
        }
    }

    /** 分支 A / 分支 B（业务设计 §4.3）：事务已回滚，同步回补预扣 */
    private EnrollResultVO handleBizFailure(long userId, long courseId, BizException e) {
        if (e.getResultCode() == ResultCode.ALREADY_ENROLLED) {
            // 分支 A：DB 已有有效报名。回补本次预扣，并把用户补回集合与 DB 对齐，对用户返回成功语义
            stockCacheService.rollbackPreDeduct(courseId, userId);
            stockCacheService.addUserBack(courseId, userId);
            return EnrollResultVO.alreadyEnrolledResult();
        }
        if (e.getResultCode() == ResultCode.SOLD_OUT) {
            // 分支 B：Redis 库存虚高（预扣成功但 DB 售罄），回补并告警，差异由对账任务修正
            log.warn("[一致性告警] 课程 {} DB 售罄但 Redis 预扣成功", courseId);
            stockCacheService.rollbackPreDeduct(courseId, userId);
        }
        throw e;
    }

    /** 分支 C（业务设计 §4.3-C）：事务结果未知，先反查确认，不能盲目回补 */
    private EnrollResultVO handleUnknownFailure(long userId, long courseId, Exception cause) {
        log.error("课程 {} 用户 {} 报名事务结果未知", courseId, userId, cause);
        try {
            if (enrollmentMapper.existsActive(userId, courseId)) {
                return EnrollResultVO.success();
            }
            stockCacheService.rollbackPreDeduct(courseId, userId);
        } catch (Exception query) {
            // 反查也失败：宁可暂时少卖，不回补，留待对账任务兜底
            log.error("[一致性告警] 报名结果反查失败，留待对账任务兜底: course={}, user={}", courseId, userId, query);
        }
        throw new BizException(ResultCode.SYSTEM_BUSY);
    }

    @Override
    public void cancel(long userId, long courseId) {
        transactionTemplate.executeWithoutResult(tx -> {
            if (enrollmentMapper.cancelEnrollment(userId, courseId) == 0) {
                throw new BizException(ResultCode.NOT_ENROLLED);
            }
            courseMapper.restoreStock(courseId);
        });
        // 取消方向：先 DB 后回补 Redis，中间态只可能表现为少卖（业务设计 §4.4）
        stockCacheService.rollbackPreDeduct(courseId, userId);
    }

    @Override
    public List<EnrollmentVO> myEnrollments(long userId) {
        return enrollmentMapper.selectMyEnrollments(userId);
    }

    private void checkEnrollWindow(long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (course.getStatus() == Course.STATUS_NOT_STARTED || now.isBefore(course.getEnrollStart())) {
            throw new BizException(ResultCode.NOT_STARTED);
        }
        if (course.getStatus() != Course.STATUS_OPEN || now.isAfter(course.getEnrollEnd())) {
            throw new BizException(ResultCode.ENDED);
        }
    }
}
