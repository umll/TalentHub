package com.talenthub.model.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 对账修正记录出参（管理端）。 */
@Getter
@Setter
public class ReconcileLogVO {

    private Long id;
    private Long courseId;
    private String courseTitle;
    private Long redisStock;
    private Integer dbStock;
    private String action;
    private OffsetDateTime createdAt;
}
