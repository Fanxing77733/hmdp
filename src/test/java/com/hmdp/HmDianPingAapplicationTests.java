package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ShopServiceImplTest {

    @Autowired
    private ShopServiceImpl shopService;

    @Test
    void testWarmUpCache() {
        // 预热店铺ID为1的数据
        shopService.saveShopToRedis(1L, 30L);
        System.out.println("预热完成");
    }
}

