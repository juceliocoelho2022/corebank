package com.corebank.event.infrastructure;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    @Test
    void shouldContinueAcrossHandledRowsAndStopWhenNoMoreEventsAreClaimable() {
        OutboxPublicationWorker worker = mock(OutboxPublicationWorker.class);
        when(worker.publishNext()).thenReturn(true, true, false);
        OutboxPublisher publisher = new OutboxPublisher(worker, properties(100));

        assertThat(publisher.publishPendingBatch()).isEqualTo(2);
        verify(worker, times(3)).publishNext();
    }

    @Test
    void shouldRespectConfiguredBatchSize() {
        OutboxPublicationWorker worker = mock(OutboxPublicationWorker.class);
        when(worker.publishNext()).thenReturn(true);
        OutboxPublisher publisher = new OutboxPublisher(worker, properties(3));

        assertThat(publisher.publishPendingBatch()).isEqualTo(3);
        verify(worker, times(3)).publishNext();
    }

    @Test
    void shouldStopCurrentBatchWhenPublicationStateCannotBePersisted() {
        OutboxPublicationWorker worker = mock(OutboxPublicationWorker.class);
        when(worker.publishNext())
                .thenReturn(true, true)
                .thenThrow(new IllegalStateException("database unavailable"));
        OutboxPublisher publisher = new OutboxPublisher(worker, properties(10));

        assertThat(publisher.publishPendingBatch()).isEqualTo(2);
        verify(worker, times(3)).publishNext();
    }

    @Test
    void batchSizeOne_shouldCallWorkerAtMostOnce() {
        OutboxPublicationWorker worker = mock(OutboxPublicationWorker.class);
        when(worker.publishNext()).thenReturn(true);
        OutboxPublisher publisher = new OutboxPublisher(worker, properties(1));

        assertThat(publisher.publishPendingBatch()).isEqualTo(1);
        verify(worker).publishNext();
    }

    private static OutboxProperties properties(int batchSize) {
        return new OutboxProperties(
                batchSize,
                2000,
                5,
                List.of(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(30)));
    }
}
