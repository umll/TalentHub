package com.talenthub.service;

/** 抢课限流（业务设计 §7）：保护 DB，不承担防超卖职责。超限抛 BizException(RATE_LIMITED)。 */
public interface RateLimitService {

    /** 课程维度接口级限流 */
    void checkCourseLimit(long courseId);

    /** 用户维度限流：同一用户同一课程 N 秒内最多 1 次，挡连点与脚本 */
    void checkUserLimit(long userId, long courseId);
}
