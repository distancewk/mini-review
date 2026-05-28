package com.hmdp;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
class HmDianPingContextTests {

    @MockBean
    private RedissonClient redissonClient;

    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @Test
    void contextLoads() {
    }
}
