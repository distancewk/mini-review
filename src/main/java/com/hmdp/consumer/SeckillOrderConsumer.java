package com.hmdp.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 秒杀订单 Kafka 消费者
 * <p>
 * 从 seckill.order.topic 消费订单消息，异步处理订单入库。
 * 消费失败自动重试（最多 3 次），超过重试次数后进入死信队列 seckill.order.topic.DLT。
 * 以 userId 作为消息 key，保证同一用户的订单路由到同一分区，顺序消费。
 */
@Slf4j
@Component
public class SeckillOrderConsumer {

    @Autowired
    private IVoucherOrderService voucherOrderService;
    @Autowired
    private ObjectMapper objectMapper;

    @KafkaListener(topics = "seckill.order.topic", concurrency = "3")
    public void handleOrder(String message, Acknowledgment ack) {
        try {
            // 反序列化订单消息（Jackson 支持 LocalDateTime 的标准 ISO 序列化）
            VoucherOrder voucherOrder = objectMapper.readValue(message, VoucherOrder.class);
            log.debug("收到订单消息: {}", voucherOrder.getId());

            // 处理订单
            voucherOrderService.handleVoucherOrder(voucherOrder);

            // 处理成功，手动确认
            ack.acknowledge();
        } catch (Exception e) {
            log.error("消费订单消息失败: {}", message, e);
            // 不调用 ack.acknowledge()，由 SeekToCurrentErrorHandler 处理重试和死信
            throw new RuntimeException(e);
        }
    }
}
