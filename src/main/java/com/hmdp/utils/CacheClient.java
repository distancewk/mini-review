package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Long time, TimeUnit unit, Class<R> type, Function<ID,R> dbFallback) {
        String key = keyPrefix + id;
        //1.从redis中查询缓存
        String shopjson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopjson)){
            //3，存在，直接返回

            return JSONUtil.toBean(shopjson, type);
        }
        if (shopjson != null){
            return null;
        }
        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.数据库不存在，返回错误
        if (r == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        this.set(key,  r, time, unit);
        //6.存在，写入redis
        return r;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis中查询缓存
        String shopjson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(shopjson)){
            //3，存在，直接返回
            return null;
        }
        //命中，需要先将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopjson, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime  expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回店铺信息
            return r;
        }

        //5.2已过期，缓存重建
        //6.缓存重建
        //6.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean lock = tryLock(lockKey);
        //6.2判断是否获取成功
        if (lock){
            //6.2成功，重建缓存
            //双重检验
            if (expireTime.isAfter(LocalDateTime.now())){

                return r;
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //重建缓存
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }
            });

        }


        return r;
    }
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


}
