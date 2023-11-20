package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        // 默认不传参 展示商铺类型列表
        // 先进行缓存查询
        String key = "shop_type_list";
        int start = 0;
        int end = -1;
        List<String> shopTypeList = stringRedisTemplate.opsForList().range(key, start, end);
        List<ShopType> shopTypes = new ArrayList<>();
        if(shopTypeList.size() != 0){
            int size = shopTypeList.size();

            for (int i = 0; i < size; i++) {
                ShopType shopType = JSONUtil.toBean(shopTypeList.get(i), ShopType.class);
                shopTypes.add(shopType);
            }
            return Result.ok(shopTypes);
        }
        // 缓存未查到 查数据库并写入缓存
        List<ShopType> shopType = this.list();
        if(shopType == null){
            return Result.fail("未找到商铺类型");
        }
        int size = shopType.size();
        for (int i = 0; i < size; i++) {
            String shopTypeStr = JSONUtil.toJsonStr(shopType.get(i));
            // 写入缓存
            stringRedisTemplate.opsForList().rightPush(key + shopType.get(i).getId(), shopTypeStr);
        }
        return Result.ok(shopType);
    }
}
