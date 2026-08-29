package com.talenthub.service.impl;

import com.talenthub.service.AuthService;
import org.springframework.stereotype.Service;

/**
 * 演示实现：直接放行。
 * 真实实现应校验报名归属（查 enrollment.user_id）与管理员角色，
 * 不通过时抛 BizException(ResultCode.FORBIDDEN)。
 */
@Service
public class AuthServiceImpl implements AuthService {

    @Override
    public void checkEnrollmentOwner(long userId, long courseId) {
        // 预留：演示阶段放行
    }

    @Override
    public void checkAdmin(long userId) {
        // 预留：演示阶段放行
    }
}
