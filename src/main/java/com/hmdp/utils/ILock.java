package com.hmdp.utils;

/**
 * @author louis
 * @version 1.0
 * @date 2023/11/16 16:29
 */
public interface ILock {
    /**
     * 非阻塞获取锁
     * @param timeoutSec
     * @return
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
