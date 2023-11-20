package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData<T> {
    /**
     * 逻辑过期时间
     */
    private LocalDateTime expireTime;
    /**
     * 存储数据类型
     */
    private T data;
}
