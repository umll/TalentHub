package com.talenthub.common;

/** Redis key 统一在此拼装（工程设计 §3.3），业务代码禁止手写 key 字符串。 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 课程 Redis 侧剩余库存（String） */
    public static String courseStock(long courseId) {
        return "course:stock:" + courseId;
    }

    /** 课程已预扣用户集合（Set） */
    public static String courseUsers(long courseId) {
        return "course:users:" + courseId;
    }

    /** 课程维度接口限流计数 */
    public static String courseRateLimit(long courseId) {
        return "rl:course:" + courseId;
    }

    /** 用户 + 课程维度限流计数 */
    public static String userRateLimit(long userId, long courseId) {
        return "rl:user:" + userId + ":" + courseId;
    }
}
