package com.talenthub.service.impl;

import com.talenthub.common.BizException;
import com.talenthub.common.RedisKeys;
import com.talenthub.common.ResultCode;
import com.talenthub.service.StockCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockCacheServiceImpl implements StockCacheService {

    private static final int ROLLBACK_RETRY_TIMES = 3;
    private static final long ROLLBACK_RETRY_INTERVAL_MS = 100;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> enrollScript;
    private final DefaultRedisScript<Long> rollbackScript;

    @Override
    public PreDeductResult preDeduct(long courseId, long userId) {
        Long result;
        try {
            result = redisTemplate.execute(enrollScript,
                    List.of(RedisKeys.courseStock(courseId), RedisKeys.courseUsers(courseId)),
                    String.valueOf(userId));
        } catch (Exception e) {
            log.error("预扣脚本执行失败: course={}, user={}", courseId, userId, e);
            throw new BizException(ResultCode.SYSTEM_BUSY);
        }
        if (result == null) {
            throw new BizException(ResultCode.SYSTEM_BUSY);
        }
        return switch (result.intValue()) {
            case 1 -> PreDeductResult.PRE_DEDUCTED;
            case 0 -> PreDeductResult.SOLD_OUT;
            case -1 -> PreDeductResult.DUPLICATE;
            case -2 -> PreDeductResult.NOT_PREHEATED;
            default -> throw new BizException(ResultCode.SYSTEM_BUSY);
        };
    }

    @Override
    public boolean rollbackPreDeduct(long courseId, long userId) {
        for (int attempt = 1; attempt <= ROLLBACK_RETRY_TIMES; attempt++) {
            try {
                redisTemplate.execute(rollbackScript,
                        List.of(RedisKeys.courseStock(courseId), RedisKeys.courseUsers(courseId)),
                        String.valueOf(userId));
                return true;
            } catch (Exception e) {
                log.warn("回补预扣失败(第 {} 次): course={}, user={}", attempt, courseId, userId, e);
                try {
                    Thread.sleep(ROLLBACK_RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("[一致性告警] 回补预扣重试耗尽，留待对账任务兜底: course={}, user={}", courseId, userId);
        return false;
    }

    @Override
    public void addUserBack(long courseId, long userId) {
        try {
            redisTemplate.opsForSet().add(RedisKeys.courseUsers(courseId), String.valueOf(userId));
        } catch (Exception e) {
            log.error("[一致性告警] 用户回填集合失败，留待对账任务兜底: course={}, user={}", courseId, userId, e);
        }
    }

    @Override
    public void preheat(long courseId, int stock, List<Long> enrolledUserIds, OffsetDateTime expireAt) {
        String stockKey = RedisKeys.courseStock(courseId);
        String usersKey = RedisKeys.courseUsers(courseId);
        redisTemplate.opsForValue().set(stockKey, String.valueOf(stock));
        redisTemplate.delete(usersKey);
        if (!enrolledUserIds.isEmpty()) {
            String[] members = enrolledUserIds.stream().map(String::valueOf).toArray(String[]::new);
            redisTemplate.opsForSet().add(usersKey, members);
        }
        redisTemplate.expireAt(stockKey, expireAt.toInstant());
        if (!enrolledUserIds.isEmpty()) {
            redisTemplate.expireAt(usersKey, expireAt.toInstant());
        }
    }

    @Override
    public Long getStock(long courseId) {
        try {
            String value = redisTemplate.opsForValue().get(RedisKeys.courseStock(courseId));
            return value == null ? null : Long.parseLong(value);
        } catch (Exception e) {
            log.warn("读取 Redis 库存失败: course={}", courseId, e);
            return null;
        }
    }

    @Override
    public boolean isPreheated(long courseId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.courseStock(courseId)));
    }
}
