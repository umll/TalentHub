package com.talenthub.mapper;

import com.talenthub.model.dto.CourseSaveDTO;
import com.talenthub.model.entity.Course;
import com.talenthub.model.vo.CourseVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CourseMapper {

    List<CourseVO> selectAllWithEnrolled(@Param("userId") long userId);

    CourseVO selectVoById(@Param("id") long id, @Param("userId") long userId);

    Course selectById(@Param("id") long id);

    int insert(Course course);

    /** 仅未开始(status=0)的课程可修改，affected=0 表示不可编辑 */
    int updateEditable(@Param("id") long id, @Param("dto") CourseSaveDTO dto);

    /** 防超卖最终防线：条件扣减，affected=0 表示已售罄或状态不符（业务设计 §2.2） */
    int deductStock(@Param("id") long courseId);

    /** 取消报名回补名额 */
    int restoreStock(@Param("id") long courseId);

    /** 到点开抢：0 → 1 */
    int openDueCourses();

    /** 到点截止：1 → 2 */
    int closeDueCourses();

    /** 未开始且 5 分钟内开抢的课程（预热候选，业务设计 §6） */
    List<Course> selectPreheatCandidates();

    List<Course> selectByStatus(@Param("status") int status);
}
