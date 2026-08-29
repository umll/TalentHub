package com.talenthub.model.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 与 course 表一一对应，仅在 service/mapper 层流转，永不返回前端。 */
@Getter
@Setter
public class Course {

    public static final int STATUS_NOT_STARTED = 0;
    public static final int STATUS_OPEN = 1;
    public static final int STATUS_ENDED = 2;
    public static final int STATUS_CANCELED = 3;

    private Long id;
    private String title;
    private Integer totalQuota;
    private Integer stock;
    private OffsetDateTime enrollStart;
    private OffsetDateTime enrollEnd;
    private Integer status;
    private Integer version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
