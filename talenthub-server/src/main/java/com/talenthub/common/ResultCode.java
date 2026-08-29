package com.talenthub.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 业务码集中定义，前端按 code 区分提示语义。 */
@Getter
@RequiredArgsConstructor
public enum ResultCode {

    OK(0, "成功"),

    BAD_REQUEST(40000, "请求参数有误"),
    UNAUTHORIZED(40100, "请先选择演示用户（缺少 X-User-Id）"),
    FORBIDDEN(40300, "无权限执行该操作"),
    NOT_FOUND(40400, "资源不存在"),
    RATE_LIMITED(42900, "当前抢课人数过多，请稍后重试"),

    SOLD_OUT(41001, "名额已满"),
    NOT_STARTED(41002, "报名尚未开始"),
    ENDED(41003, "报名已截止"),
    NOT_PREHEATED(41004, "课程报名未开放，请稍后重试"),
    NOT_ENROLLED(41005, "您未报名该课程，无法取消"),
    ALREADY_ENROLLED(41006, "您已报名该课程"),
    COURSE_NOT_EDITABLE(41007, "仅未开始的课程可修改"),

    SYSTEM_BUSY(50001, "系统繁忙，请重试"),
    ERROR(50000, "系统内部错误");

    private final int code;
    private final String message;
}
