package ai.codecraft.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.service.ModelConnectionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.stereotype.Component;
import org.springframework.mock.env.MockEnvironment;

class CopilotBrowserBudgetTest {
    @Test
    void isDiscoverableButExcludedOutsideDisposableValidation() {
        assertThat(CopilotBrowserBudget.class.isAnnotationPresent(Component.class)).isTrue();
        new ApplicationContextRunner().withUserConfiguration(CopilotBrowserBudget.class)
            .run(context -> assertThat(context).doesNotHaveBean(CopilotBrowserBudget.class));
    }

    @Test
    void credentialProvisioningCannotStartWithoutTheActualDriverGuard() {
        var driver = mock(ModelCallDriver.class);
        assertThatThrownBy(() -> new CopilotBrowserFixtureController(mock(ModelConnectionService.class), new MockEnvironment(),
            new CopilotBrowserBudget(), driver)).isInstanceOf(IllegalStateException.class).hasMessageContaining("budget was not installed");
    }

    @Test
    void springInstalledGuardCountsEachEntryOnceAndRejectsBeforeTheThirteenthDispatch() throws Exception {
        var original = mock(ModelCallDriver.class);
        when(original.call(any(), any(), any(), anyInt(), any())).thenCallRealMethod();
        new ApplicationContextRunner().withPropertyValues("COPILOT_BROWSER_E2E=disposable")
            .withUserConfiguration(CopilotBrowserBudget.class).withBean(ModelCallDriver.class, () -> original).run(context -> {
                var guarded = context.getBean(ModelCallDriver.class);
                assertThat(guarded).isNotSameAs(original);
                assertThat(context.getBean(CopilotBrowserBudget.class).guards(guarded)).isTrue();
                for (int count = 0; count < 12; count++) guarded.call(null, null, null, 1, Instant.EPOCH);
                assertThatThrownBy(() -> guarded.call(null, null, null, 1, Instant.EPOCH)).isInstanceOf(BusinessException.class).hasMessage("MODEL_OUTPUT_LIMIT_EXCEEDED");
                verify(original, times(12)).call(any(), any(), any(), anyInt(), any(), any());
                guarded.close(); verify(original).close();
            });
    }
}
