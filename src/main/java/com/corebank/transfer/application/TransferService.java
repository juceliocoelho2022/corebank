package com.corebank.transfer.application;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountStatus;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.event.application.FinancialEventFactory;
import com.corebank.event.application.OutboxService;
import com.corebank.ledger.application.LedgerService;
import com.corebank.shared.exception.InvalidAccountStateException;
import com.corebank.shared.exception.ResourceNotFoundException;
import com.corebank.transaction.domain.AccountTransaction;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final FinancialEventFactory eventFactory;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher events;

    public TransferService(
            AccountRepository accountRepository,
            AccountTransactionRepository transactionRepository,
            LedgerService ledgerService,
            FinancialEventFactory eventFactory,
            OutboxService outboxService,
            ApplicationEventPublisher events) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerService = ledgerService;
        this.eventFactory = eventFactory;
        this.outboxService = outboxService;
        this.events = events;
    }

    @Transactional
    public TransferResult transfer(UUID sourceAccountId, UUID destinationAccountId,
                                   BigDecimal amount, String description) {
        requireId(sourceAccountId, "Source account id is required");
        requireId(destinationAccountId, "Destination account id is required");
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new IllegalArgumentException("Source and destination accounts must be different");
        }

        BigDecimal normalizedAmount = movementAmount(amount);
        String normalizedDescription = normalizeDescription(description);

        Account source = accountRepository.findById(sourceAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("Source account not found"));
        Account destination = accountRepository.findById(destinationAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("Destination account not found"));

        ensureTransferable(source);
        ensureTransferable(destination);

        BigDecimal sourceBefore = source.withdraw(normalizedAmount);
        BigDecimal destinationBefore = destination.deposit(normalizedAmount);
        UUID transferId = UUID.randomUUID();

        accountRepository.saveAndFlush(source);
        accountRepository.saveAndFlush(destination);

        transactionRepository.save(AccountTransaction.transfer(
                sourceAccountId, TransactionType.TRANSFER_OUT, normalizedAmount,
                sourceBefore, source.getBalance(), normalizedDescription, transferId, destinationAccountId));
        transactionRepository.save(AccountTransaction.transfer(
                destinationAccountId, TransactionType.TRANSFER_IN, normalizedAmount,
                destinationBefore, destination.getBalance(), normalizedDescription, transferId, sourceAccountId));

        ledgerService.postTransfer(
                sourceAccountId,
                destinationAccountId,
                transferId,
                normalizedAmount,
                normalizedDescription);

        outboxService.record(eventFactory.transferCompleted(
                transferId,
                sourceAccountId,
                destinationAccountId,
                normalizedAmount));

        events.publishEvent(new TransferCommittedEvent());

        return new TransferResult(
                transferId,
                sourceAccountId,
                destinationAccountId,
                normalizedAmount,
                source.getBalance(),
                destination.getBalance(),
                Instant.now());
    }

    private static void ensureTransferable(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidAccountStateException(account.getStatus().name());
        }
        if (!Account.DEFAULT_CURRENCY.equals(account.getCurrency())) {
            throw new IllegalArgumentException("Only BRL accounts can participate in transfers");
        }
    }

    private static BigDecimal movementAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw new IllegalArgumentException("Amount must be positive and have at most two decimal places");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String normalizeDescription(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 200) {
            throw new IllegalArgumentException("Description must have at most 200 characters");
        }
        return trimmed;
    }

    private static void requireId(UUID value, String message) {
        if (value == null) throw new IllegalArgumentException(message);
    }
}
