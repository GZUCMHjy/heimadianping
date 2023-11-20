package com.hmdp.utils;

import org.apache.tomcat.jni.Local;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author louis
 * @version 1.0
 * @date 2023/11/3 8:54
 */
@Component
public class RedisIdWorker {
    private StringRedisTemplate redisTemplate;
    private static final int COUNT_BITS = 32;
    private static final long BEGIN_TIMESTAMP = 1577808000L;
    // 加载到IOC容器需要构造方法 自动注入
    public RedisIdWorker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Redis自增策略
     * @param keyPrefix
     * @return
     */
    public long nextId(String keyPrefix){
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 2. 生成序列号
        // 2.1 获取当前日期时间
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 自增长 防止超过2^64 因此加上当前日期作为后缀
        long count = redisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 3. 拼接
        // 位运算 ＋ 或运算
        return timestamp << COUNT_BITS | count;
    }
}
