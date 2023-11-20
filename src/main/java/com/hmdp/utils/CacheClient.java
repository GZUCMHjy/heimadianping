package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author louis
 * @version 1.0
 * @date 2023/11/2 10:41
 */
@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate redisTemplate;

    public CacheClient(StringRedisTemplate redisTemplate){
        this.redisTemplate = redisTemplate;
    }


    public void set(String key, Object value, Long time, TimeUnit unit){
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 写入逻辑过期
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        // 设置逻辑过期 单独创建一个RedisData（秉持着单一职责，开闭原则）
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }
    // 设置泛型类型 使之传参类型根据灵活性
    // 参数Class<R> 灵活接收各种类型（classType）的参数

    /**
     * 解决缓存穿透
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R ,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 通过Redis查询商铺信息缓存
        String json = redisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){
            // 存在  进行反序列化
//            return Result.ok(shop);
            return JSONUtil.toBean(json,type);
        }
        // 判断命中是否是空值
        // 增加这一步（提前返回空值，缓存null值） 是为了防止缓存穿透的问题（避免不合法的请求参数 进行多次查询数据库）
        // 未在Redis查到相关数据
        if(json != null){
            // 不等于空，相当于就是空字符串
            this.set(key,"",CACHE_NULL_TTL,unit);
            return null;
        }
        // 不存在
        // 查询数据库
        R r = dbFallback.apply(id);
        if(r== null){
            return null;
        }
        // 存在 写入redis 并确定超时时间 可以自定义确定缓存时间（那就通过调用者进行传参）
        this.set(key,r,time,unit);
        return r;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //Function<ID,R> dbFallback
    // 表示传参时ID 返回值是R

    /**
     * 解决热点key击穿问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R ,ID> R queryWithLogicalExpire(String keyPrefix,ID id,
                                            Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1. 通过Redis查询缓存中商品信息
        String shopJson = redisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
            // 1.1 不存在
            return null;
        }
        //2. 命中
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 强转Shop 先通过转换成JSON对象
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 2.1 判断过期时间
        if(expireTime.isAfter(LocalDateTime.now())){
            // 未过期
            return r;
        }
        // 3. 过期，需要缓存重建
        // 3.1 缓存重建(说白了，就是查一次数据库，写入一次redis)
        // 3.2 获取互斥锁
        String localkey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(localkey);
        // 判断是否获取锁
        if(isLock){
            // 成功 开启独立线程（且不被其他线程打断）
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                // 重建缓存
                try {
                    // 查数据库
                    R r1 = dbFallback.apply(id);
                    // 写Redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unLock(localkey);
                }
            });
        }
        return r;
    }
    private boolean tryLock(String key){
        // Redis本身写入时是有互斥的效果，利用这点进行加锁操作
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        // 进行拆箱操作 避免对象出现空指针异常的错误
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        redisTemplate.delete(key);
    }
}
