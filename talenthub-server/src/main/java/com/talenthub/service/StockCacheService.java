package com.talenthub.service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Redis 库存操作的唯一入口（工程设计 §3.3）：
 * Lua 返回码在此转为语义化枚举，业务代码不接触裸数字。
 */
public interface StockCacheService {

    enum PreDeductResult {
        /** 预扣成功，可进入 DB 事务 */
        PRE_DEDUCTED,
        /** 库存不足 */
        SOLD_OUT,
        /** 该用户已预扣过（重复报名） */
        DUPLICATE,
        /** 库存未预热，拒绝报名（业务设计 §3.2） */
        NOT_PREHEATED
    }

    PreDeductResult preDeduct(long courseId, long userId);

    /**
     * 回补预扣（幂等，内部重试 3 次，业务设计 §4.3）。
     * 返回 false 表示 Redis 侧执行失败，已记告警日志，留待对账任务兜底。
     */
    boolean rollbackPreDeduct(long courseId, long userId);

    /** 分支 A：DB 已有有效报名时把用户补回集合，使集合与 DB 对齐（业务设计 §4.3-A） */
    void addUserBack(long courseId, long userId);

    /** 预热 / 重建：写入库存与已报名用户集合，设置统一过期时间（业务设计 §6） */
    void preheat(long courseId, int stock, List<Long> enrolledUserIds, OffsetDateTime expireAt);

    /** Redis 侧库存，null 表示未预热 */
    Long getStock(long courseId);

    boolean isPreheated(long courseId);
}
