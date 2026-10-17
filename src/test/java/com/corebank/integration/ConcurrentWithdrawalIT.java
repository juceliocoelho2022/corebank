package com.corebank.integration;

import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.transaction.domain.AccountTransaction;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ConcurrentWithdrawalIT {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired AccountTransactionRepository transactionRepository;
    @Autowired PlatformTransactionManager transactionManager;
    private UUID accountId;

    @BeforeEach void setUp(){ transactionRepository.deleteAll(); accountRepository.deleteAll(); customerRepository.deleteAll(); Customer customer=customerRepository.saveAndFlush(new Customer("Ana Silva",UUID.randomUUID().toString(),"ana."+UUID.randomUUID()+"@example.com")); Account account=new Account(customer.getId(),"10008882"); account.deposit(new BigDecimal("100.00")); accountId=accountRepository.saveAndFlush(account).getId(); }

    @Test void concurrentWithdrawals_shouldNeverProduceNegativeBalance() throws Exception {
        CountDownLatch bothLoaded=new CountDownLatch(2), releaseUpdates=new CountDownLatch(1); ExecutorService pool=Executors.newFixedThreadPool(2);
        Callable<String> withdrawal=()->{ TransactionTemplate template=new TransactionTemplate(transactionManager); try { template.executeWithoutResult(status->{ Account account=accountRepository.findById(accountId).orElseThrow(); BigDecimal before=account.withdraw(new BigDecimal("80.00")); bothLoaded.countDown(); await(releaseUpdates); accountRepository.saveAndFlush(account); transactionRepository.saveAndFlush(new AccountTransaction(accountId,TransactionType.WITHDRAWAL,new BigDecimal("80.00"),before,account.getBalance(),"concurrent test")); }); return "SUCCESS"; } catch(ObjectOptimisticLockingFailureException ex){ return "CONFLICT"; } };
        Future<String> first=pool.submit(withdrawal), second=pool.submit(withdrawal); bothLoaded.await(); releaseUpdates.countDown(); List<String> outcomes=List.of(first.get(),second.get()); pool.shutdownNow();
        assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS","CONFLICT"); assertThat(accountRepository.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("20.00"); assertThat(transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId)).hasSize(1);
    }
    private static void await(CountDownLatch latch){ try { latch.await(); } catch(InterruptedException ex){ Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while coordinating concurrency test",ex); } }
}
