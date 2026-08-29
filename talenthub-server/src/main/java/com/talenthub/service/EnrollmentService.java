package com.talenthub.service;

import com.talenthub.model.vo.EnrollResultVO;
import com.talenthub.model.vo.EnrollmentVO;

import java.util.List;

public interface EnrollmentService {

    /** 抢课报名主链路（业务设计 §4.1）：限流 → Redis 预扣 → DB 事务 → 失败同步回补 */
    EnrollResultVO enroll(long userId, long courseId);

    /** 取消报名（业务设计 §4.4）：先 DB 后回补 Redis */
    void cancel(long userId, long courseId);

    List<EnrollmentVO> myEnrollments(long userId);
}
