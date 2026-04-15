package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private static final String STREAM_NAME = "stream.orders";
    private static final String GROUP_NAME = "g1";
    private static final String CONSUMER_NAME = "c1";

    @PostConstruct
    private void init() {
        try {
            stringRedisTemplate.opsForStream().createGroup(STREAM_NAME, ReadOffset.from("0"), GROUP_NAME);
        } catch (Exception e) {
            log.info("消费者组已存在");
        }

        SECKILL_ORDER_EXECUTOR.submit(() -> {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, CONSUMER_NAME),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            //消息队列的名字和读取位置
                            StreamOffset.create(STREAM_NAME, ReadOffset.lastConsumed())
                    );

                    if (records == null || records.isEmpty()) {
                        continue;
                    }

                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> values = record.getValue();

                    VoucherOrder voucherOrder = new VoucherOrder();
                    voucherOrder.setId(Long.valueOf(values.get("orderId").toString()));
                    voucherOrder.setUserId(Long.valueOf(values.get("userId").toString()));
                    voucherOrder.setVoucherId(Long.valueOf(values.get("voucherId").toString()));

                    handleVoucherOrder(voucherOrder);

                    //确认消息ACK
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_NAME, GROUP_NAME, record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingMessages();
                }
            }
        });
    }

    private void handlePendingMessages() {
        try {
            List<MapRecord<String, Object, Object>> pendingRecords = stringRedisTemplate.opsForStream().read(
                    Consumer.from(GROUP_NAME, CONSUMER_NAME),
                    StreamReadOptions.empty().count(1),
                    StreamOffset.create(STREAM_NAME, ReadOffset.from("0"))
            );

            if (pendingRecords != null && !pendingRecords.isEmpty()) {
                MapRecord<String, Object, Object> record = pendingRecords.get(0);
                Map<Object, Object> values = record.getValue();

                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setId(Long.valueOf(values.get("orderId").toString()));
                voucherOrder.setUserId(Long.valueOf(values.get("userId").toString()));
                voucherOrder.setVoucherId(Long.valueOf(values.get("voucherId").toString()));

                handleVoucherOrder(voucherOrder);
                stringRedisTemplate.opsForStream().acknowledge(STREAM_NAME, GROUP_NAME, record.getId());
            }
        } catch (Exception e) {
            log.error("处理Pending消息异常", e);
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString()
        );

        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "活动尚未开始" : r == 2 ? "库存不足" : "不能重复下单");
        }

        return Result.ok(orderId);
    }

    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.warn("用户{}重复购买券{}", userId, voucherId);
            return;
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("秒杀库存扣减失败，券ID: {}", voucherId);
            return;
        }

        save(voucherOrder);
        log.info("订单创建成功，订单ID: {}", voucherOrder.getId());
    }

    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("不能重复下单");
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(voucherOrder.getId());
    }
}



//单体锁
        //synchronized (userId.toString().intern()) {

        //分布式锁
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //redisson获取锁
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            System.out.println("获取锁失败");
//            return Result.fail("请勿重复下单");
//        }
//        try {
//
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            Result result = proxy.createVoucherOrder(voucherId);
//            System.out.println("createVoucherOrder返回结果: " + result);
//            return result;
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            return Result.fail("服务器异常");
//        } finally {
//            lock.unlock();
//        }
//
//    }





