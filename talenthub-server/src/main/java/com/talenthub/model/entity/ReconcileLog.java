package com.talenthub.model.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 对账修正审计记录（业务设计 §5：修正动作全部落审计日志）。 */
@Getter
@Setter
public class ReconcileLog {

    private Long id;
    private Long courseId;
    private Long redisStock;
    private Integer dbStock;
    private String action;
    private OffsetDateTime createdAt;
}
