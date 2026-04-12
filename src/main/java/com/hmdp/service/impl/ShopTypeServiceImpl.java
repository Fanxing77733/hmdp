package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 查询所有商铺类型
     * @return 商铺类型列表
     */
    public List<ShopType> queryList() {
        //1.查询redis是否存在
        String shopTypeJson = stringRedisTemplate.opsForValue().get("cache:shop:type");
        if (shopTypeJson != null && !shopTypeJson.isEmpty()) {
            //2.存在返回
            List<ShopType> shopTypes = JSONUtil.toList(shopTypeJson, ShopType.class);
            return shopTypes;
        }
        //3.不存在查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //5.不存在，返回错误
        if (shopTypes == null || shopTypes.isEmpty()) {
            return null;
        }

        stringRedisTemplate.opsForValue().set("cache:shop:type", JSONUtil.toJsonStr(shopTypes));
        return shopTypes;





    }
}
