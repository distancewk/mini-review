package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.SeekToCurrentErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 配置：重试机制与死信队列
 */
@Configuration
public class KafkaConfig {

    @Bean
    public SeekToCurrentErrorHandler errorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        // 创建死信发布恢复器：重试耗尽后发送到死信队列（自动添加 .DLQ 后缀）
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        // 重试间隔 1 秒，最大重试 3 次（加上首次执行共 4 次）
        FixedBackOff backOff = new FixedBackOff(1000L, 3L);

        return new SeekToCurrentErrorHandler(recoverer, backOff);
    }
}
