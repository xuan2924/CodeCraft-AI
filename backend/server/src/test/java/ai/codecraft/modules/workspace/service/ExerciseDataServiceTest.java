package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExerciseDataServiceTest {
    @Test
    void countsAllDomainEvidenceAndDoesNotEraseAnyDomainBeforeAllAreQuiescent() {
        var first = mock(ExerciseDataContributor.class);
        var second = mock(ExerciseDataContributor.class);
        var service = new ExerciseDataService(List.of(first, second));
        when(first.retainedBytes("exercise")).thenReturn(1024L);
        when(second.retainedBytes("exercise")).thenReturn(2048L);
        assertThat(service.retainedBytes("exercise")).isEqualTo(3072);
        when(first.quiescent("exercise")).thenReturn(true);
        when(second.quiescent("exercise")).thenReturn(false);
        assertThatThrownBy(() -> service.erase("exercise")).isInstanceOf(IllegalStateException.class);
        verify(first, never()).erase("exercise");
        when(second.quiescent("exercise")).thenReturn(true);
        service.erase("exercise");
        verify(first).erase("exercise");
        verify(second).erase("exercise");
    }

    @Test
    void invalidOrOverflowingMeasurementsCannotBecomeFreeCapacity() {
        var first = mock(ExerciseDataContributor.class);
        var second = mock(ExerciseDataContributor.class);
        var service = new ExerciseDataService(List.of(first, second));
        when(first.retainedBytes("exercise")).thenReturn(-1L);
        assertThatThrownBy(() -> service.retainedBytes("exercise")).isInstanceOf(IllegalStateException.class);
        when(first.retainedBytes("exercise")).thenReturn(Long.MAX_VALUE);
        when(second.retainedBytes("exercise")).thenReturn(1L);
        assertThatThrownBy(() -> service.retainedBytes("exercise")).isInstanceOf(ArithmeticException.class);
    }
}
