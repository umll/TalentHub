package com.talenthub.model.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 与 enrollment 表一一对应。 */
@Getter
@Setter
public class Enrollment {

    public static final int STATUS_ENROLLED = 1;
    public static final int STATUS_CANCELED = 2;

    private Long id;
    private Long userId;
    private Long courseId;
    private Integer status;
    private OffsetDateTime enrolledAt;
    private OffsetDateTime canceledAt;
    private OffsetDateTime createdAt;
}
