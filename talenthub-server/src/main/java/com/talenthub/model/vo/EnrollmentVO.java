package com.talenthub.model.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 我的报名列表出参。 */
@Getter
@Setter
public class EnrollmentVO {

    private Long id;
    private Long courseId;
    private String courseTitle;
    private Integer status;
    private OffsetDateTime enrolledAt;
    private OffsetDateTime canceledAt;
    /** 课程当前状态：报名中(1)才允许取消 */
    private Integer courseStatus;
    private OffsetDateTime enrollEnd;
}
