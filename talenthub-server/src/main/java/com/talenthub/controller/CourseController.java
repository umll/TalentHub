package com.talenthub.controller;

import com.talenthub.common.Result;
import com.talenthub.common.UserContext;
import com.talenthub.model.vo.CourseVO;
import com.talenthub.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    @GetMapping
    public Result<List<CourseVO>> list() {
        return Result.ok(courseService.list(UserContext.currentUserId()));
    }

    @GetMapping("/{id}")
    public Result<CourseVO> detail(@PathVariable long id) {
        return Result.ok(courseService.detail(id, UserContext.currentUserId()));
    }
}
