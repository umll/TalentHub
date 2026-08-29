package com.talenthub.controller;

import com.talenthub.common.Result;
import com.talenthub.common.UserContext;
import com.talenthub.model.dto.CourseSaveDTO;
import com.talenthub.model.vo.ReconcileLogVO;
import com.talenthub.service.AuthService;
import com.talenthub.service.CourseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminCourseController {

    private final AuthService authService;
    private final CourseService courseService;

    @PostMapping("/courses")
    public Result<Long> create(@Valid @RequestBody CourseSaveDTO dto) {
        authService.checkAdmin(UserContext.currentUserId());
        return Result.ok(courseService.create(dto));
    }

    @PutMapping("/courses/{id}")
    public Result<Void> update(@PathVariable long id, @Valid @RequestBody CourseSaveDTO dto) {
        authService.checkAdmin(UserContext.currentUserId());
        courseService.update(id, dto);
        return Result.ok();
    }

    @PostMapping("/courses/{id}/preheat")
    public Result<Void> preheat(@PathVariable long id) {
        authService.checkAdmin(UserContext.currentUserId());
        courseService.preheat(id);
        return Result.ok();
    }

    @GetMapping("/reconcile-logs")
    public Result<List<ReconcileLogVO>> reconcileLogs() {
        authService.checkAdmin(UserContext.currentUserId());
        return Result.ok(courseService.recentReconcileLogs());
    }
}
