package com.talenthub.model.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 课程列表 / 详情出参。报名中的课程 stock 展示 Redis 实时值（CourseService 覆盖）。 */
@Getter
@Setter
public class CourseVO {

    private Long id;
    private String title;
    private Integer totalQuota;
    private Integer stock;
    private OffsetDateTime enrollStart;
    private OffsetDateTime enrollEnd;
    private Integer status;
    /** 当前用户是否已报名 */
    private Boolean enrolled;
}
