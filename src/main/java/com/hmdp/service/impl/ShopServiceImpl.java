package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.CacheClient.CACHE_REBUILD_EXECUTOR;
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


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = queryWithMutex(id);
        Shop shop = queryWithLogicExpire(id);
        if (shop == null){
            return Result.fail("查询店铺为空!");
        }
        return Result.ok(shop);
    }

    @Override
    public Result update(Shop shop) {
        // 1.先操作数据库
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        // 2.再删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    /**
     * 获取互斥锁
     * @param key
     * @return
     */
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 删除互斥锁
     * @param key
     */
    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 缓存击穿（基于逻辑过期实现）
     * @param id
     * @return
     */
    public Shop queryWithLogicExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.未查询到，直接返回
        if (StrUtil.isBlank(shopJson)){
            return null;
        }
        // 3.查询到了,需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        //4.判断是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期直接返回
            return shop;
        }
        //5.过期的话需要缓存重建
        //5.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean hasLock = tryLock(lockKey);
        //5.2判断是否获取到，获取到:根据id查数据库 获取不到:休眠
        if(hasLock){
            //成功就开启独立线程，实现缓存重建, 这里的话用线程池
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存,从mysql中查，然后更新redis
                    this.saveShop2Redis(id,20L);
                    //this.saveShop2Redis(id,1800L); 正常业务过期时间应该设置30min，上面是为了便于测试
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        return shop;
    }

    /**
     * 缓存击穿（基于互斥锁解决）
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.查询到了，直接返回
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空字符串(将空值写入redis，避免缓存穿透时，存入的)
        if ("".equals(shopJson)){
            return null;
        }
        // 3.未查询到,根据id mysql查询
        // 3.1获取互斥锁，获取到了就根据id查数据库，获取不到就休眠，再尝试
        String lockKey= LOCK_SHOP_KEY + id;
        Shop shop = null;
        try{
            boolean hasLock = tryLock(lockKey);
            if (!hasLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 走到这就说明获取到了互斥锁，接下来查询数据库
            shop = getById(id);
            // 模拟重建的延时
            Thread.sleep(200);
            // 4.不存在，返回错误
            if (shop == null){
                // 将空值写入redis，避免缓存穿透
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            // 5.存在，写入redis，返回
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        }catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

    /**
     * 解决缓存穿透（往redis里面存空对象解决）
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.查询到了，直接返回
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空字符串(将空值写入redis，避免缓存穿透时，存入的)
        if ("".equals(shopJson)){
            return null;
        }
        // 3.未查询到,根据id mysql查询
        Shop shop = getById(id);
        // 4.不存在，返回错误
        if (shop == null){
            // 将空值写入redis，避免缓存穿透
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        // 5.存在，写入redis，返回
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        return null;
    }

    /**
     * 将添加了逻辑过期时间的shop数据放入redis
     * @param id
     * @param expireSeconds
     * @throws InterruptedException
     */
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺信息
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

}
