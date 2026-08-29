package com.talenthub.controller;

import com.talenthub.common.Result;
import com.talenthub.common.UserContext;
import com.talenthub.model.vo.EnrollResultVO;
import com.talenthub.model.vo.EnrollmentVO;
import com.talenthub.service.AuthService;
import com.talenthub.service.EnrollmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class EnrollmentController {

    private final AuthService authService;
    private final EnrollmentService enrollmentService;

    @PostMapping("/courses/{courseId}/enroll")
    public Result<EnrollResultVO> enroll(@PathVariable long courseId) {
        // 抢课操作的是"自己"的报名，无需 owner 校验（工程设计 §3.2）
        long userId = UserContext.currentUserId();
        return Result.ok(enrollmentService.enroll(userId, courseId));
    }

    @DeleteMapping("/courses/{courseId}/enroll")
    public Result<Void> cancel(@PathVariable long courseId) {
        long userId = UserContext.currentUserId();
        authService.checkEnrollmentOwner(userId, courseId);
        enrollmentService.cancel(userId, courseId);
        return Result.ok();
    }

    @GetMapping("/enrollments/my")
    public Result<List<EnrollmentVO>> myEnrollments() {
        return Result.ok(enrollmentService.myEnrollments(UserContext.currentUserId()));
    }
}
