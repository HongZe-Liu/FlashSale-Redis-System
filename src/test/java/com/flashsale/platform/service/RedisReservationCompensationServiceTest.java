package com.flashsale.platform.service;

import com.flashsale.platform.observability.BusinessMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class RedisReservationCompensationServiceTest {

    private static final Long OFFER_ID = 101L;
    private static final Long USER_ID = 501L;
    private static final Long ORDER_ID = 9001L;
    private static final String REASON = "rabbitmq_dead_letter:create_order_exception";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private BusinessMetrics businessMetrics;

    private RedisReservationCompensationService compensationService;

    @BeforeEach
    void setUp() {
        compensationService = new RedisReservationCompensationService();
        ReflectionTestUtils.setField(compensationService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(compensationService, "businessMetrics", businessMetrics);
    }

    @Test
    void compensate_whenArgumentsMissing_returnsFalseWithoutExecutingRedis() {
        boolean compensated = compensationService.compensate(OFFER_ID, null, ORDER_ID, REASON);

        assertThat(compensated).isFalse();
        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), any(), any());
        verify(businessMetrics).recordRedisCompensationFailure("invalid_arguments");
    }

    @Test
    void compensate_whenScriptReturnsSuccess_recordsSuccess() {
        redisScriptReturning(1L);

        boolean compensated = compensationService.compensate(OFFER_ID, USER_ID, ORDER_ID, REASON);

        assertThat(compensated).isTrue();
        verify(businessMetrics).recordRedisCompensationSuccess(REASON);
    }

    @Test
    void compensate_whenStockKeyMissing_recordsFailure() {
        redisScriptReturning(2L);

        boolean compensated = compensationService.compensate(OFFER_ID, USER_ID, ORDER_ID, REASON);

        assertThat(compensated).isFalse();
        verify(businessMetrics).recordRedisCompensationFailure("stock_key_missing");
    }

    @Test
    void compensate_whenReservationAlreadyMissing_recordsNoop() {
        redisScriptReturning(0L);

        boolean compensated = compensationService.compensate(OFFER_ID, USER_ID, ORDER_ID, REASON);

        assertThat(compensated).isFalse();
        verify(businessMetrics).recordRedisCompensationNoop(REASON);
    }

    @Test
    void compensate_whenScriptResultIsNull_recordsFailure() {
        redisScriptReturning(null);

        boolean compensated = compensationService.compensate(OFFER_ID, USER_ID, ORDER_ID, REASON);

        assertThat(compensated).isFalse();
        verify(businessMetrics).recordRedisCompensationFailure("script_result_null");
    }

    @Test
    void compensate_whenRedisThrows_recordsFailureAndRethrows() {
        RuntimeException redisException = new RuntimeException("redis down");
        doThrow(redisException).when(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(Collections.emptyList()),
                eq(OFFER_ID.toString()),
                eq(USER_ID.toString())
        );

        assertThatThrownBy(() -> compensationService.compensate(OFFER_ID, USER_ID, ORDER_ID, REASON))
                .isSameAs(redisException);
        verify(businessMetrics).recordRedisCompensationFailure("redis_exception");
    }

    private void redisScriptReturning(Long result) {
        doReturn(result).when(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(Collections.emptyList()),
                eq(OFFER_ID.toString()),
                eq(USER_ID.toString())
        );
    }
}
