package com.talenthub.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 管理端创建 / 修改课程入参（修改仅允许未开始的课程，见 CourseService）。 */
@Getter
@Setter
public class CourseSaveDTO {

    @NotBlank(message = "课程名称不能为空")
    private String title;

    @NotNull(message = "总名额不能为空")
    @Min(value = 1, message = "总名额至少为 1")
    private Integer totalQuota;

    @NotNull(message = "报名开始时间不能为空")
    private OffsetDateTime enrollStart;

    @NotNull(message = "报名截止时间不能为空")
    private OffsetDateTime enrollEnd;
}
