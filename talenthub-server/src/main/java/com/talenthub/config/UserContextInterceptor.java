package com.talenthub.config;

import com.talenthub.common.ResultCode;
import com.talenthub.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 解析 X-User-Id 头写入用户上下文。演示形态的登录简化，接入真实认证时替换本类。 */
@Component
public class UserContextInterceptor implements HandlerInterceptor {

    private static final String USER_ID_HEADER = "X-User-Id";

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        String header = request.getHeader(USER_ID_HEADER);
        long userId;
        try {
            userId = Long.parseLong(header);
        } catch (NumberFormatException | NullPointerException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":" + ResultCode.UNAUTHORIZED.getCode()
                    + ",\"message\":\"" + ResultCode.UNAUTHORIZED.getMessage() + "\",\"data\":null}");
            return false;
        }
        UserContext.set(userId);
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        UserContext.clear();
    }
}
