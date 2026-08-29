package com.talenthub.common;

/** 当前请求用户上下文，由拦截器从 X-User-Id 头解析写入（演示形态的登录简化）。 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(long userId) {
        CURRENT_USER.set(userId);
    }

    public static long currentUserId() {
        Long userId = CURRENT_USER.get();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
