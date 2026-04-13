package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //单体锁
        //synchronized (userId.toString().intern()) {

        //分布式锁
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        boolean isLock = lock.tryLock(100L);
        if (!isLock) {
            System.out.println("获取锁失败");
            return Result.fail("请勿重复下单");
        }
        try {

            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            Result result = proxy.createVoucherOrder(voucherId);
            System.out.println("createVoucherOrder返回结果: " + result);
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("服务器异常");
        } finally {
            lock.unlock();
        }

    }



    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        System.out.println("========== 进入createVoucherOrder方法 ==========");
        Long userId = UserHolder.getUser().getId();
        System.out.println("用户ID: " + userId + ", 券ID: " + voucherId);

        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        System.out.println("查询到的优惠券: " + voucher);

        if (voucher == null) {
            System.out.println("优惠券不存在");
            return Result.fail("优惠券不存在");
        }

        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            System.out.println("秒杀尚未开始");
            return Result.fail("秒杀尚未开始");
        }

        // 3. 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            System.out.println("秒杀已结束");
            return Result.fail("秒杀已结束");
        }

        // 4. 判断库存是否充足
        if (voucher.getStock() < 1) {
            System.out.println("库存不足，当前库存: " + voucher.getStock());
            return Result.fail("库存不足");
        }

        // 5. 一人一单校验
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        System.out.println("已购买数量: " + count);
        if (count > 0) {
            return Result.fail("不能重复下单");
        }

        // 6. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        System.out.println("扣减库存结果: " + success);
        if (!success) {
            return Result.fail("库存不足");
        }

        // 7. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        System.out.println("订单创建成功，订单ID: " + voucherOrder.getId());

        // 8. 返回订单id
        return Result.ok(voucherOrder.getId());
    }
}
