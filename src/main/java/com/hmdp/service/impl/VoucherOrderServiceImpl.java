package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.annotation.Resource;
import java.util.Collections;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("mapper/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        if (result == null) {
            return Result.fail("抢购失败");
        }

        // 2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 3.创建订单对象
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);

        // 4.发送消息到Kafka（以userId为key确保同一用户路由到同一分区，保证顺序消费）
        String jsonStr;
        try {
            jsonStr = objectMapper.writeValueAsString(voucherOrder);
        } catch (Exception e) {
            log.error("订单序列化失败, orderId: {}", orderId, e);
            compensateSeckill(voucherId, userId);
            return Result.fail("订单创建失败");
        }
        try {
            kafkaTemplate.send("seckill.order.topic", userId.toString(), jsonStr)
                    .addCallback(
                            sendResult -> log.debug("订单消息发送成功, orderId: {}", orderId),
                            ex -> {
                                log.error("订单消息发送失败, orderId: {}", orderId, ex);
                                compensateSeckill(voucherId, userId);
                            }
                    );
        } catch (Exception e) {
            log.error("订单消息发送失败, orderId: {}", orderId, e);
            compensateSeckill(voucherId, userId);
            return Result.fail("订单创建失败");
        }

        // 5.返回订单id
        return Result.ok(orderId);
    }

    private void compensateSeckill(Long voucherId, Long userId) {
        try {
            stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
            stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
        } catch (Exception e) {
            log.error("秒杀Redis补偿失败, voucherId: {}, userId: {}", voucherId, userId, e);
        }
    }

    /**
     * 处理订单（由Kafka消费者调用）
     */
    @Override
    public void handleVoucherOrder(VoucherOrder order) {
        Long userId = order.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        if (!lock.tryLock()) {
            log.error("不允许重复下单");
            throw new RuntimeException("获取分布式锁失败，userId: " + userId);
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(order);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买过一次");
            return;
        }
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }

        save(voucherOrder);
    }
}
