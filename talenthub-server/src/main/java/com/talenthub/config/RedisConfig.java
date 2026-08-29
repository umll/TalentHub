package com.talenthub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/** Lua 脚本随应用启动加载为 Bean，脚本文件在代码仓库统一管理（业务设计 §3）。 */
@Configuration
public class RedisConfig {

    @Bean
    public DefaultRedisScript<Long> enrollScript() {
        return loadScript("scripts/redis/enroll.lua");
    }

    @Bean
    public DefaultRedisScript<Long> rollbackScript() {
        return loadScript("scripts/redis/rollback.lua");
    }

    @Bean
    public DefaultRedisScript<Long> rateLimitScript() {
        return loadScript("scripts/redis/rate_limit.lua");
    }

    private DefaultRedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }
}
