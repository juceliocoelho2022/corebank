package com.corebank.event.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    @Test
    void shouldStopWhenNoMoreEventsAreClaimable() {
        OutboxPublicationWorker worker = mock(OutboxPublicationWorker.class);
        when(worker.publishNext()).thenReturn(true, true, false);
        OutboxPublisher publisher = new OutboxPublisher(worker, new OutboxProperties(100, 2000));

        assertThat(publisher.publishPendingBatch()).isEqualTo(2);
        verify(worker, times(3)).publishNext();
    }

    @Test
    void shouldRespectConfiguredBatchSize() {
        OutboxPublicationWorker worker = mock(OutboxPublicationWorker.class);
        when(worker.publishNext()).thenReturn(true);
        OutboxPublisher publisher = new OutboxPublisher(worker, new OutboxProperties(3, 2000));

        assertThat(publisher.publishPendingBatch()).isEqualTo(3);
        verify(worker, times(3)).publishNext();
    }

    @Test
    void shouldStopCurrentBatchAfterPublicationFailure() {
        OutboxPublicationWorker worker = mock(OutboxPublicationWorker.class);
        when(worker.publishNext())
                .thenReturn(true, true)
                .thenThrow(new IllegalStateException("Kafka unavailable"));
        OutboxPublisher publisher = new OutboxPublisher(worker, new OutboxProperties(10, 2000));

        assertThat(publisher.publishPendingBatch()).isEqualTo(2);
        verify(worker, times(3)).publishNext();
    }

    @Test
    void batchSizeOne_shouldCallWorkerAtMostOnce() {
        OutboxPublicationWorker worker = mock(OutboxPublicationWorker.class);
        when(worker.publishNext()).thenReturn(true);
        OutboxPublisher publisher = new OutboxPublisher(worker, new OutboxProperties(1, 2000));

        assertThat(publisher.publishPendingBatch()).isEqualTo(1);
        verify(worker).publishNext();
    }
}
