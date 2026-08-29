package com.talenthub.service;

/**
 * 水平数据权限校验（工程设计 §3.2）。
 * 演示阶段实现类直接放行，接入真实权限体系时仅替换实现，controller 调用位置不变。
 */
public interface AuthService {

    /** 校验当前用户是否可操作该课程下自己的报名数据 */
    void checkEnrollmentOwner(long userId, long courseId);

    /** 校验当前用户是否具备管理端操作权限 */
    void checkAdmin(long userId);
}
