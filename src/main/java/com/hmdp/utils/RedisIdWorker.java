package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final long BEGIN_TIMESTAMP = 1640995200L; // 2022-01-01 00:00:00 的秒级时间戳

    private static final int COUNT_BITS = 32; // 序列号占用位数

    /**
     * 生成全局唯一 ID
     * @param keyPrefix 业务前缀（如 "order"、"voucher"）
     * @return 全局唯一 ID
     */
    public long nextId(String keyPrefix) {
        // 1. 生成时间戳（秒级）
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        if (count == null) {
            throw new RuntimeException("生成序列号失败");
        }

        // 3. 拼接并返回（时间戳左移 32 位 + 序列号）
        return timestamp << COUNT_BITS | count;
    }

    /**
     * 测试方法：验证 ID 生成逻辑
     */
    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("起始时间戳: " + second);
    }
}
