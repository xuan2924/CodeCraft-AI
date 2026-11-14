package ai.codecraft.fixture;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Loaded only by the disposable paid-browser override, never packaged in the application image. */
@Component
@ConditionalOnProperty(name = "COPILOT_BROWSER_E2E", havingValue = "disposable")
public class CopilotBrowserBudget implements BeanPostProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(CopilotBrowserBudget.class);
    private static final int CALL_LIMIT = 12;
    private final AtomicInteger calls = new AtomicInteger();
    private final MethodInterceptor guard = invocation -> {
        if ("call".equals(invocation.getMethod().getName())) {
            int attempt = calls.incrementAndGet();
            if (attempt > CALL_LIMIT) throw new BusinessException(ModelAccessErrorCode.MODEL_OUTPUT_LIMIT_EXCEEDED);
            LOG.info("live_browser_dispatch attempt={} limit={}", attempt, CALL_LIMIT);
        }
        return invocation.proceed();
    };

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof ModelCallDriver)) return bean;
        var proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(true); proxy.addAdvice(guard);
        LOG.info("live_browser_budget_installed limit={}", CALL_LIMIT);
        return proxy.getProxy();
    }

    public boolean guards(ModelCallDriver driver) {
        return driver instanceof Advised advised && Arrays.stream(advised.getAdvisors()).anyMatch(advisor -> advisor.getAdvice() == guard);
    }
}
