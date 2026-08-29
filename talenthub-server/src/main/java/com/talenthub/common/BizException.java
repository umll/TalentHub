package com.talenthub.common;

import lombok.Getter;

/** 业务异常：service 层业务失败的唯一表达方式，由全局异常处理器统一转响应。 */
@Getter
public class BizException extends RuntimeException {

    private final ResultCode resultCode;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }
}
