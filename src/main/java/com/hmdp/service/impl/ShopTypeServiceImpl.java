package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = CACHE_SHOP_TYPE_KEY;
        // 1. 根据key去redis查询相关缓存
        List<String> typeJson = stringRedisTemplate.opsForList().range(key, 0, -1);
        // 2. 从缓存中查到了
        if (CollectionUtil.isNotEmpty(typeJson)){
            // 2.0 如果为空对象(防止缓存穿透时存入的空对象)
            if (StrUtil.isBlank(typeJson.get(0))) {
                return Result.fail("商品分类信息为空！");
            }
            // 2.1 命中则转换List<String> -> List<ShopType> 并返回
            List<ShopType> typeList = new ArrayList<>();
            for (String jsonString : typeJson) {
                ShopType shopType = JSONUtil.toBean(jsonString, ShopType.class);
                typeList.add(shopType);
            }
            return Result.ok(typeList);
        }
        // 3. 缓存中没找到，去mysql查
        LambdaQueryWrapper<ShopType> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.orderByAsc(ShopType::getSort);
        List<ShopType> typeList = this.list(queryWrapper);
        // 3.1 数据库中不存在
        if (CollectionUtil.isEmpty(typeList)) {
            // 添加空对象到redis，解决缓存穿透
            stringRedisTemplate.opsForList().rightPushAll(key, CollectionUtil.newArrayList(""));
            stringRedisTemplate.expire(key,CACHE_SHOP_TYPE_TTL,TimeUnit.MINUTES);
            // 返回错误
            return Result.fail("商品分类信息为空！");
        }
        // 3.2 数据库中存在,转换List<ShopType> -> List<String> 类型
        List<String> shopTypeList = new ArrayList<>();
        for (ShopType shopType : typeList) {
            String jsonStr = JSONUtil.toJsonStr(shopType);
            shopTypeList.add(jsonStr);
        }
        // 4.写入redis缓存, 有顺序只能RPUSH
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypeList);
        stringRedisTemplate.expire(key,CACHE_SHOP_TYPE_TTL,TimeUnit.MINUTES);
        // 5. 返回
        return Result.ok(typeList);
    }
}
