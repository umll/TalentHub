package com.talenthub.mapper;

import com.talenthub.model.vo.EnrollmentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EnrollmentMapper {

    /**
     * 报名 upsert：新报名 / 曾取消后重新报名 / 重复报名 三合一（业务设计 §4.1）。
     * affected=1 报名成功；affected=0 该用户已持有有效报名。
     */
    int upsertEnrollment(@Param("userId") long userId, @Param("courseId") long courseId);

    /** affected=0 表示无可取消的有效报名 */
    int cancelEnrollment(@Param("userId") long userId, @Param("courseId") long courseId);

    /** 是否存在有效报名（分支 C 反查用，业务设计 §4.3-C） */
    boolean existsActive(@Param("userId") long userId, @Param("courseId") long courseId);

    List<EnrollmentVO> selectMyEnrollments(@Param("userId") long userId);

    int countActive(@Param("courseId") long courseId);

    /** 有效报名用户 ID 列表（预热 / 对账重建用户集合用） */
    List<Long> selectActiveUserIds(@Param("courseId") long courseId);
}
