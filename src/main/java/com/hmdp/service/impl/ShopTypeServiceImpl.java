package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

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
   private StringRedisTemplate stringredisTemplate;
    @Override
    public Result typeList() {
        List<String> shoptype = stringredisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE_KEY,0,-1);
        List<ShopType> shopTypeList = new ArrayList<>();
        if (!shoptype.isEmpty()){
            for (String s : shoptype) {
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                shopTypeList.add(shopType);
            }
            return Result.ok(shopTypeList);
        }
        shopTypeList =query().orderByAsc("sort").list();
        if (shopTypeList.isEmpty()){
            return Result.fail("分类不存在");
        }
        for (ShopType shopType : shopTypeList) {
            stringredisTemplate.opsForList().leftPush(RedisConstants.CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(shopType));
        }
        return Result.ok(shopTypeList);

    }
}
