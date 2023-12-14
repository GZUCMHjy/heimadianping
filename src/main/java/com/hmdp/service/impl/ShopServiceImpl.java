package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.sql.Time;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    /**
     * 根据商铺id查询
     * @param id
     * @return
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Resource
    private CacheClient cacheClient;
    // 调用Java写好的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Override
    public Result queryById(Long id) {
        //  1. 缓存穿透
        // Shop shop = queryWithPassThrough(id);
        // 2. 互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
//        if(shop == null){
//            return Result.fail("店铺不存在");
//        }
        // 3. 逻辑过期时间解决缓存雪崩
//        Shop shop = queryWithLogicalExpire(id);
//        id2 -> getById(id2) 缩略形式
        // 进行封装 简化代码
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return Result.ok(shop);
//        Shop shop = queryWithPassThrough(id);

        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);

    }

    /**
     * 解决缓存穿透问题
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1. 通过Redis查询商铺信息缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            // 存在  进行反序列化
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 2. 判断命中是否是空值
        if(shopJson != null){
            // 不等于空，相当于就是空字符串
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 3. 不存在, 查询数据库
        Shop shop = getById(id);
        // 不存在，返回空值
        if(shop == null){
            return null;
        }
        // 存在 写入redis 并确定超时时间
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 互斥锁＋缓存空值---->解决缓存击穿+缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id ){
        String key = CACHE_SHOP_KEY + id;
        // 通过Redis查询商铺信息缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        if(StrUtil.isNotBlank(shopJson)){
            // 存在  进行反序列化
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson != null){
        // 空字符串,实现缓存重建
        // 1. 获取互斥锁
            try {
                boolean isLock = tryLock(lockKey);
                // 2. 判断是否获取锁
                if(!isLock){
                    // 3.失败进行睡眠 然后再进行重试
                    Thread.sleep(50);
                    return  queryWithMutex(id);
                }
                // 4. 成功获取锁 先进行double check 然后再考虑是否进行数据库查询
                String cache = stringRedisTemplate.opsForValue().get(key);
                if(!cache.isEmpty()){
                    return JSONUtil.toBean(cache,Shop.class);
                }
                shop = getById(id);
                // 数据库未找到该数据,缓存数据为空字符串，同时设置超时时间
                if(shop == null){
                    // 写入Redis（set）
                    stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                // 存在 写入redis 并确定超时时间
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY,JSONUtil.toJsonStr(shop),CACHE_NULL_TTL, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }finally {
                // 最终进行释放锁
                unLock(lockKey);
            }
        }
        return shop;
    }


    /**
     * 逻辑过期 实现 缓存雪崩的情况
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1. 通过Redis查询缓存中商品信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
            // 1.1 不存在
            return null;
        }
        //2. 命中
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 强转Shop 先通过转换成JSON对象
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        Shop shop1 = JSONUtil.toBean((String) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 2.1 判断过期时间
        if(expireTime.isAfter(LocalDateTime.now())){
            // 未过期
            return shop;
        }
        // 3. 过期，需要缓存重建
        // 3.1 缓存重建(说白了，就是查一次数据库，写入一次redis)
        // 3.2 获取互斥锁
        String localkey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(localkey);
        // 判断是否获取锁
        if(isLock){
            // 成功 开启独立线程（且不被其他线程打断）提高性能（开启多线程）
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                // 重建缓存
                try {
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unLock(localkey);
                }
            });
        }
        return shop;
    }
    private boolean tryLock(String key){
        // Redis本身写入时是有互斥的效果，利用这点进行加锁操作
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        // 进行拆箱操作 避免对象出现空指针异常的错误
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        // 删除键值 （相当于释放锁资源，让其他相同的key可以有机会写入Redis）
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds){
        // 1. 查询店铺信息
        Shop shop = getById(id);
        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入Redis （要将数据对象进行序列化toJsonStr）
        // stringRedisTemplate的key和value值都是String类型的
        // toJsonStr方法是将对象转换成json字符串
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    /**
     * 根据商铺类型查询商铺（地理坐标）
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    @Override
    public Result queryShopByTypeId(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否需要根据坐标查询
        if( x == null || y == null){
            // 默认分页查询
            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 2. 计算分页
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3. 查询redis 、按距离排序、分页 结果shopId distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results  = stringRedisTemplate.opsForGeo().search(
                key, // 查找对应的key
                GeoReference.fromCoordinate(x, y), // 指定对应点坐标
                new Distance(5000), // 半径长度 默认是米
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end) // 限制半径距离参数end（边界值）
        );

        // 4. 解析出id
        if(results == null){
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        List<String> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        // 4.1 截取从from - end 的部分
        if (list.size() < from) {
            // 跳过了，直接返回空数组
            return Result.ok();
        }
        list.stream().skip(from).forEach(result ->
                // 获取店铺id
                {
                    String shopIdStr = result.getContent().getName();
                    // 记录店铺的id
                    ids.add(shopIdStr);
                    Distance distance = result.getDistance();
                    // 记录与当前用户距离
                    distanceMap.put(shopIdStr,distance);
                }
                );

        // 5. 根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD (id," + idStr + ")").list();
        for (Shop shop : shops) {
           shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
