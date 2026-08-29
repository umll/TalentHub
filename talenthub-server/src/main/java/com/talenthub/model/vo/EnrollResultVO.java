package com.talenthub.model.vo;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 抢课结果。"已报名"对用户是成功语义（业务设计 §4.2），以字段区分而非错误码。 */
@Getter
@RequiredArgsConstructor
public class EnrollResultVO {

    private final boolean alreadyEnrolled;

    public static EnrollResultVO success() {
        return new EnrollResultVO(false);
    }

    public static EnrollResultVO alreadyEnrolledResult() {
        return new EnrollResultVO(true);
    }
}
