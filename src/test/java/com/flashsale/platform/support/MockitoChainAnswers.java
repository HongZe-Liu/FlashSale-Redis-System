package com.flashsale.platform.support;

import org.mockito.Answers;
import org.mockito.invocation.InvocationOnMock;

public final class MockitoChainAnswers {

    private MockitoChainAnswers() {
    }

    public static Object returnsSelfForChainMethods(InvocationOnMock invocation) throws Throwable {
        Class<?> returnType = invocation.getMethod().getReturnType();
        if (returnType == Object.class || returnType.isInstance(invocation.getMock())) {
            return invocation.getMock();
        }
        return Answers.RETURNS_DEFAULTS.answer(invocation);
    }
}
