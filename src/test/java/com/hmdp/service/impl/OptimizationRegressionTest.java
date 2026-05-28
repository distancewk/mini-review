package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class OptimizationRegressionTest {

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void seckillScriptTreatsMissingStockAsSoldOut() throws Exception {
        ClassPathResource resource = new ClassPathResource("mapper/seckill.lua");
        String script = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

        assertEquals(true, script.contains("if (stock == false or stock == nil) then"));
    }

    @Test
    void seckillVoucherCompensatesRedisWhenKafkaFutureFails() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);

        when(redisTemplate.execute(any(), anyList(), anyString(), anyString())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisIdWorker.nextId("order")).thenReturn(99L);
        SettableListenableFuture<SendResult<String, String>> failedFuture = new SettableListenableFuture<>();
        failedFuture.setException(new RuntimeException("kafka down"));
        when(kafkaTemplate.send(eq("seckill.order.topic"), eq("7"), anyString())).thenReturn(failedFuture);

        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "kafkaTemplate", kafkaTemplate);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "seckillVoucherService", mock(ISeckillVoucherService.class));
        UserDTO user = new UserDTO();
        user.setId(7L);
        user.setNickName("u7");
        UserHolder.saveUser(user);

        Result result = service.seckillVoucher(3L);

        assertTrue(result.getSuccess());
        assertEquals(99L, result.getData());
        verify(valueOperations).increment(RedisConstants.SECKILL_STOCK_KEY + 3L);
        verify(setOperations).remove("seckill:order:" + 3L, "7");
    }

    @Test
    void seckillVoucherCompensatesRedisWhenKafkaSendThrowsSynchronously() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);

        when(redisTemplate.execute(any(), anyList(), anyString(), anyString())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisIdWorker.nextId("order")).thenReturn(101L);
        when(kafkaTemplate.send(eq("seckill.order.topic"), eq("9"), anyString())).thenThrow(new RuntimeException("producer unavailable"));

        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "kafkaTemplate", kafkaTemplate);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "seckillVoucherService", mock(ISeckillVoucherService.class));
        UserDTO user = new UserDTO();
        user.setId(9L);
        user.setNickName("u9");
        UserHolder.saveUser(user);

        Result result = service.seckillVoucher(5L);

        assertFalse(result.getSuccess());
        assertEquals("订单创建失败", result.getErrorMsg());
        verify(valueOperations).increment(RedisConstants.SECKILL_STOCK_KEY + 5L);
        verify(setOperations).remove("seckill:order:" + 5L, "9");
    }

    @Test
    void seckillVoucherCompensatesRedisWhenOrderSerializationFails() throws Exception {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        when(redisTemplate.execute(any(), anyList(), anyString(), anyString())).thenReturn(0L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisIdWorker.nextId("order")).thenReturn(100L);
        when(objectMapper.writeValueAsString(any(VoucherOrder.class))).thenThrow(new RuntimeException("json error"));

        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "seckillVoucherService", mock(ISeckillVoucherService.class));
        UserDTO user = new UserDTO();
        user.setId(8L);
        user.setNickName("u8");
        UserHolder.saveUser(user);

        Result result = service.seckillVoucher(4L);

        assertFalse(result.getSuccess());
        assertEquals("订单创建失败", result.getErrorMsg());
        verify(valueOperations).increment(RedisConstants.SECKILL_STOCK_KEY + 4L);
        verify(setOperations).remove("seckill:order:" + 4L, "8");
    }

    @Test
    void updateShopRejectsMissingIdBeforeDatabaseAndCache() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        doReturn(true).when(service).updateById(any(Shop.class));

        Result result = service.update(new Shop());

        assertFalse(result.getSuccess());
        assertEquals("店铺id不能为空", result.getErrorMsg());
        verify(service, org.mockito.Mockito.never()).updateById(any(Shop.class));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void shopTypeCacheMissHandlesNullRedisList() {
        ShopTypeServiceImpl service = spy(new ShopTypeServiceImpl());
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        org.springframework.data.redis.core.ListOperations<String, String> listOperations = mock(org.springframework.data.redis.core.ListOperations.class);
        ShopTypeMapper shopTypeMapper = mock(ShopTypeMapper.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1)).thenReturn(null);
        when(shopTypeMapper.selectList(any())).thenReturn(Collections.emptyList());

        ReflectionTestUtils.setField(service, "stringredisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "baseMapper", shopTypeMapper);

        Result result = service.typeList();

        assertFalse(result.getSuccess());
        assertEquals("分类不存在", result.getErrorMsg());
    }

    @Test
    void logoutDeletesTokenFromRedis() {
        UserServiceImpl service = new UserServiceImpl();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);

        Result result = service.logout("token-1");

        assertTrue(result.getSuccess());
        verify(redisTemplate).delete(RedisConstants.LOGIN_USER_KEY + "token-1");
    }
}
