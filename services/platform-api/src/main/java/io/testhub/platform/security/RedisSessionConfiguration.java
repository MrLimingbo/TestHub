package io.testhub.platform.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

@Configuration
@org.springframework.context.annotation.Profile("!desktop")
@EnableRedisHttpSession(redisNamespace = "testhub:session", maxInactiveIntervalInSeconds = 28800)
public class RedisSessionConfiguration {
}
