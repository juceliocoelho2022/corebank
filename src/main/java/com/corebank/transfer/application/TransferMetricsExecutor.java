package com.corebank.transfer.application;

import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.exception.InvalidAccountStateException;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class TransferMetricsExecutor {

    private final TransferMetrics metrics;

    public TransferMetricsExecutor(TransferMetrics metrics) {
        this.metrics = metrics;
    }

    public <T> T execute(Supplier<T> operation) {
        long startedAt = System.nanoTime();

        try {
            return operation.get();
        } catch (InsufficientBalanceException ex) {
            metrics.rejected("insufficient_balance");
            throw ex;
        } catch (InvalidAccountStateException ex) {
            metrics.rejected("invalid_account_state");
            throw ex;
        } catch (ResourceNotFoundException ex) {
            metrics.rejected("account_not_found");
            throw ex;
        } catch (IllegalArgumentException ex) {
            metrics.rejected("invalid_request");
            throw ex;
        } finally {
            metrics.recordDuration(System.nanoTime() - startedAt);
        }
    }
}
