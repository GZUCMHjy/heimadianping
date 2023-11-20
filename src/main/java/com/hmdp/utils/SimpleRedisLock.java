package com.hmdp.utils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author louis
 * @version 1.0
 * @date 2023/11/16 16:30
 */
public class SimpleRedisLock implements ILock{
    private StringRedisTemplate redisTemplate;
    // 业务名称后缀
    private String name;
    // ctrl + shift + u (全字母大写)
    // 常量锁业务名称前缀
    private static final String KEY_PREFIX = "lock:";
    // UUID获取的字符串前缀
    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";

    // 查看继承关系 快捷键 ctrl + h
    // 静态变量 必须 赋初始值
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT ;
    // 提前加载好，避免每次获取锁都要加载，浪费时间，提高效率
    // 静态代码块，在类加载的时候执行，有且只执行一次
    static {
        UNLOCK_SCRIPT= new DefaultRedisScript<>();
        // 查找在资源路径下的unlock的文件名
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    // 并没有交给Spring进行管理，因此通过构造函数传入
    public SimpleRedisLock(StringRedisTemplate redisTemplate, String name) {
        this.redisTemplate = redisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 采用线程自带的Id属性，极有可能在集群下的JVM中会出现重复
//        long threadId = Thread.currentThread().getId();
       String threadId = ID_PREFIX + Thread.currentThread().getId() ;
       // 写入Redis
        Boolean success = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,threadId,timeoutSec, TimeUnit.SECONDS);
        // 拆箱过程（可能会出现空指针异常）
        return Boolean.TRUE.equals(success);
    }

    /**
     * 调用lua脚本 实现原子性
     */
    @Override
    public void unlock() {
        redisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

//    @Override
//    public void unlock() {
//        // 获取标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        // Redis 获取
//        String id = redisTemplate.opsForValue().get(KEY_PREFIX + name);
//        // 进行判断
//        if(threadId.equals(id)){
//            redisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
