package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;
import static java.lang.Thread.sleep;

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
    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
       // Shop shop = queryWithPassThrough(id);
        //缓存击穿
        //Shop shop = queryWithMutex(id);

        //缓存击穿逻辑
       // Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryWithLogicalExpire("cache:shop:", id, Shop.class,this::getById ,30L, TimeUnit.MINUTES);

        return Result.ok(shop);
    }

    //抽出缓存击穿的方法
    private Shop queryWithPassThrough(Long id) {
        //1.从redis里面查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);

        //2.是否存在缓存
        if (shopJson != null) {
            //3.存在返回
            if (shopJson.equals("")) {
                return null;
            }
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return null;
        }
        //4.不存在查询数据库
        Shop shop = this.getById(id);

        //5，不存在，返回错误
        if (shop == null) {
            //添加一个空值防止缓存穿透
            stringRedisTemplate.opsForValue().set("cache:shop:" + id, "",2L, TimeUnit.MINUTES);
            return null;
        }

        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(shop),30L, TimeUnit.MINUTES);
         return shop;
    }

    //利用互斥锁解决缓存击穿实现缓存重建
    public Shop queryWithMutex(Long id) {
        //1.从redis里面查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);

        //2.是否存在缓存
        if (shopJson != null) {
            //3.存在返回
            if (shopJson.equals("")) {
                return null;
            }
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return null;
        }
        //4.1尝试获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean islock = tryLock(lockKey);
            //4.2获取失败
            if (!islock) {
                //获取锁失败，休眠并重试
                Thread.sleep(50);
                //调用递归重试
                return queryWithMutex(id);
            }
            //4.3获取成功，查询数据库
            shop = this.getById(id);
            //模拟高并发
            Thread.sleep(200);

            //5，不存在，返回错误
            if (shop == null) {
                //添加一个空值防止缓存穿透
                stringRedisTemplate.opsForValue().set("cache:shop:" + id, "",2L, TimeUnit.MINUTES);
                return null;
            }

            //6.存在，写入redis
            stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(shop),30L, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放锁
            unLock(lockKey);
        }
        return shop;
    }

    //利用逻辑过期时间解决缓存击穿
    public Shop queryWithLogicalExpire(Long id) {
        //1.从Redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);

        //2.判断缓存是否存在
        if (shopJson == null) {
            //3.缓存不存在，直接返回null（不查库，由定时任务预热）
            return null;
        }

        //4.缓存存在，反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);

        //5.判断逻辑过期时间是否到期
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期，直接返回
            return shop;
        }

        //5.2 已过期，尝试获取互斥锁
        String lockKey = "lock:shop:" + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //6.获取锁成功，开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShopToRedis(id, 30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        //7.返回旧数据
        return shop;
    }
    // 线程池：用于异步重建缓存
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 将店铺数据写入Redis（带逻辑过期时间）
     * @param id 店铺ID
     * @param expireSeconds 过期时间（秒）
     */
    public void saveShopToRedis(Long id, Long expireSeconds) {
        //1. 查询数据库
        Shop shop = this.getById(id);

        //模拟重建缓存的耗时
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        //2. 封装为RedisData对象（包含逻辑过期时间）
        RedisData redisData = new RedisData();
        redisData.setData(JSONUtil.parseObj(shop));
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //3. 写入Redis（不设置TTL，永不过期）
        stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(redisData));
    }


        //设置互斥锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
    /**
     * 更新店铺信息
     * @param shop
     */
    @Transactional
    public void update(Shop shop) {

        Long id = shop.getId();
        if (id == null) {
            throw new IllegalArgumentException("店铺id不能为空");
        }
        updateById( shop);
        stringRedisTemplate.delete("cache:shop:" + id);


    }


    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }

}
