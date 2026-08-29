package com.talenthub.service.impl;

import com.talenthub.common.BizException;
import com.talenthub.common.RedisKeys;
import com.talenthub.common.ResultCode;
import com.talenthub.service.RateLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class RateLimitServiceImpl implements RateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;
    private final int courseQps;
    private final int userIntervalSeconds;

    public RateLimitServiceImpl(StringRedisTemplate redisTemplate,
                                DefaultRedisScript<Long> rateLimitScript,
                                @Value("${talenthub.rate-limit.course-qps}") int courseQps,
                                @Value("${talenthub.rate-limit.user-interval-seconds}") int userIntervalSeconds) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = rateLimitScript;
        this.courseQps = courseQps;
        this.userIntervalSeconds = userIntervalSeconds;
    }

    @Override
    public void checkCourseLimit(long courseId) {
        check(RedisKeys.courseRateLimit(courseId), 1, courseQps);
    }

    @Override
    public void checkUserLimit(long userId, long courseId) {
        check(RedisKeys.userRateLimit(userId, courseId), userIntervalSeconds, 1);
    }

    private void check(String key, int windowSeconds, int limit) {
        Long allowed;
        try {
            allowed = redisTemplate.execute(rateLimitScript, List.of(key),
                    String.valueOf(windowSeconds), String.valueOf(limit));
        } catch (Exception e) {
            // 限流是保护手段而非正确性手段：Redis 异常时放行，由 DB 最终防线兜底
            log.warn("限流脚本执行失败，本次放行: key={}", key, e);
            return;
        }
        if (allowed != null && allowed == 0) {
            throw new BizException(ResultCode.RATE_LIMITED);
        }
    }
}
