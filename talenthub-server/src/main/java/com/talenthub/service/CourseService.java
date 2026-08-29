package com.talenthub.service;

import com.talenthub.model.dto.CourseSaveDTO;
import com.talenthub.model.vo.CourseVO;
import com.talenthub.model.vo.ReconcileLogVO;

import java.util.List;

public interface CourseService {

    List<CourseVO> list(long userId);

    CourseVO detail(long courseId, long userId);

    long create(CourseSaveDTO dto);

    void update(long courseId, CourseSaveDTO dto);

    /** 库存预热：以 DB 为准写入 Redis 库存与已报名用户集合（业务设计 §6） */
    void preheat(long courseId);

    List<ReconcileLogVO> recentReconcileLogs();
}
