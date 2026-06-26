package com.flashsale.payment.controller;

import com.flashsale.payment.config.PaymentProperties;
import com.flashsale.payment.dto.MockPaymentWebhookRequest;
import com.flashsale.payment.dto.Result;
import com.flashsale.payment.service.IPaymentWebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookControllerTest {

    private MockMvc mockMvc;

    @Mock
    private IPaymentWebhookService paymentWebhookService;

    @Mock
    private PaymentProperties paymentProperties;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvcWithProfile("test");
    }

    @Test
    void mockPaymentSucceeded_whenTestProfile_passesRequestAndRawPayloadToService() throws Exception {
        when(paymentWebhookService.handleMockPaymentSucceeded(any(MockPaymentWebhookRequest.class), anyString()))
                .thenReturn(Result.ok());

        mockMvc.perform(post("/payments/webhooks/mock")
                        .contentType("application/json")
                        .content("{"
                                + "\"eventId\":\"evt_1\","
                                + "\"eventType\":\"payment.succeeded\","
                                + "\"providerPaymentId\":\"mock_pay_9001\","
                                + "\"orderId\":1001,"
                                + "\"amount\":1299,"
                                + "\"currency\":\"EUR\""
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<MockPaymentWebhookRequest> requestCaptor =
                ArgumentCaptor.forClass(MockPaymentWebhookRequest.class);
        ArgumentCaptor<String> rawPayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(paymentWebhookService).handleMockPaymentSucceeded(
                requestCaptor.capture(),
                rawPayloadCaptor.capture()
        );
        assertThat(requestCaptor.getValue().getEventId()).isEqualTo("evt_1");
        assertThat(requestCaptor.getValue().getProviderPaymentId()).isEqualTo("mock_pay_9001");
        assertThat(requestCaptor.getValue().getOrderId()).isEqualTo(1001L);
        assertThat(rawPayloadCaptor.getValue()).contains("\"eventId\":\"evt_1\"");
    }

    @Test
    void mockPaymentSucceeded_whenOutsideTestProfileAndSecretMissing_isDisabled() throws Exception {
        mockMvc = mockMvcWithProfile("prod");
        when(paymentProperties.getMockWebhookSecret()).thenReturn("expected-secret");

        mockMvc.perform(post("/payments/webhooks/mock")
                        .contentType("application/json")
                        .content(validWebhookPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("Mock webhook is disabled"));

        verifyNoInteractions(paymentWebhookService);
    }

    @Test
    void mockPaymentSucceeded_whenOutsideTestProfileAndSecretMatches_passesThrough() throws Exception {
        mockMvc = mockMvcWithProfile("prod");
        when(paymentProperties.getMockWebhookSecret()).thenReturn("expected-secret");
        when(paymentWebhookService.handleMockPaymentSucceeded(any(MockPaymentWebhookRequest.class), anyString()))
                .thenReturn(Result.ok());

        mockMvc.perform(post("/payments/webhooks/mock")
                        .header("X-Mock-Webhook-Secret", "expected-secret")
                        .contentType("application/json")
                        .content(validWebhookPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(paymentWebhookService).handleMockPaymentSucceeded(any(MockPaymentWebhookRequest.class), anyString());
    }

    private MockMvc mockMvcWithProfile(String activeProfile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfile);

        PaymentWebhookController controller = new PaymentWebhookController();
        ReflectionTestUtils.setField(controller, "paymentWebhookService", paymentWebhookService);
        ReflectionTestUtils.setField(controller, "paymentProperties", paymentProperties);
        ReflectionTestUtils.setField(controller, "environment", environment);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
        return MockMvcBuilders.standaloneSetup(controller)
                .setValidator(noOpValidator())
                .build();
    }

    private String validWebhookPayload() {
        return "{"
                + "\"eventId\":\"evt_1\","
                + "\"eventType\":\"payment.succeeded\","
                + "\"providerPaymentId\":\"mock_pay_9001\","
                + "\"orderId\":1001,"
                + "\"amount\":1299,"
                + "\"currency\":\"EUR\""
                + "}";
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
