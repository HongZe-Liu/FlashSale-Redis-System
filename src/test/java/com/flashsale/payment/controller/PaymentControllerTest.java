package com.flashsale.payment.controller;

import com.flashsale.payment.dto.CreatePaymentRequest;
import com.flashsale.payment.dto.CreatePaymentResponse;
import com.flashsale.payment.dto.PaymentStatusResponse;
import com.flashsale.payment.dto.Result;
import com.flashsale.payment.dto.UserDTO;
import com.flashsale.payment.enums.OrderStatus;
import com.flashsale.payment.enums.PaymentProviderType;
import com.flashsale.payment.enums.PaymentStatus;
import com.flashsale.payment.service.IPaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long USER_ID = 501L;

    private MockMvc mockMvc;

    @Mock
    private IPaymentService paymentService;

    @BeforeEach
    void setUp() {
        PaymentController controller = new PaymentController();
        ReflectionTestUtils.setField(controller, "paymentService", paymentService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setValidator(noOpValidator())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPayment_whenPrincipalMissing_returnsUserNotLoggedIn() throws Exception {
        mockMvc.perform(post("/payments/orders/{orderId}", ORDER_ID)
                        .contentType("application/json")
                        .content("{\"provider\":\"MOCK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("用户未登录"));
    }

    @Test
    void createPayment_whenPrincipalPresent_passesUserIdAndRequestToService() throws Exception {
        when(paymentService.createPayment(eq(ORDER_ID), eq(USER_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Result.ok(new CreatePaymentResponse(
                        9001L,
                        ORDER_ID,
                        PaymentProviderType.MOCK.name(),
                        "mock_pay_9001",
                        1299L,
                        "EUR",
                        PaymentStatus.PENDING.name(),
                        "mock://checkout/9001"
                )));

        SecurityContextHolder.getContext().setAuthentication(authenticationToken());

        mockMvc.perform(post("/payments/orders/{orderId}", ORDER_ID)
                        .contentType("application/json")
                        .content("{\"provider\":\"MOCK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.paymentOrderId").value(9001L))
                .andExpect(jsonPath("$.data.providerPaymentId").value("mock_pay_9001"));

        ArgumentCaptor<CreatePaymentRequest> requestCaptor = ArgumentCaptor.forClass(CreatePaymentRequest.class);
        verify(paymentService).createPayment(eq(ORDER_ID), eq(USER_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getProvider()).isEqualTo(PaymentProviderType.MOCK.name());
    }

    @Test
    void queryPaymentStatus_whenPrincipalPresent_passesUserIdToService() throws Exception {
        when(paymentService.queryPaymentStatus(ORDER_ID, USER_ID))
                .thenReturn(Result.ok(new PaymentStatusResponse(
                        ORDER_ID,
                        OrderStatus.PENDING_PAYMENT.name(),
                        PaymentStatus.PENDING.name(),
                        PaymentProviderType.MOCK.name(),
                        1299L,
                        "EUR"
                )));

        SecurityContextHolder.getContext().setAuthentication(authenticationToken());

        mockMvc.perform(get("/payments/orders/{orderId}", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderStatus").value(OrderStatus.PENDING_PAYMENT.name()))
                .andExpect(jsonPath("$.data.paymentStatus").value(PaymentStatus.PENDING.name()));

        verify(paymentService).queryPaymentStatus(ORDER_ID, USER_ID);
    }

    private UsernamePasswordAuthenticationToken authenticationToken() {
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        user.setNickName("Alice");
        user.setRole("USER");
        return new UsernamePasswordAuthenticationToken(
                user,
                null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    private Validator noOpValidator() {
        return new Validator() {
            @Override
            public boolean supports(Class<?> clazz) {
                return true;
            }

            @Override
            public void validate(Object target, Errors errors) {
            }
        };
    }
}
