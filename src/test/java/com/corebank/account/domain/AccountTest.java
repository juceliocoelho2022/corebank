package com.corebank.account.domain;

import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.exception.InvalidAccountStateException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {
    @Test void deposit_shouldIncreaseBalance(){ Account a=account(); BigDecimal before=a.deposit(new BigDecimal("100.00")); assertThat(before).isEqualByComparingTo("0.00"); assertThat(a.getBalance()).isEqualByComparingTo("100.00"); assertThat(a.getBalance().scale()).isEqualTo(2); }
    @Test void withdrawal_shouldDecreaseBalance(){ Account a=account(); a.deposit(new BigDecimal("100.00")); BigDecimal before=a.withdraw(new BigDecimal("30.00")); assertThat(before).isEqualByComparingTo("100.00"); assertThat(a.getBalance()).isEqualByComparingTo("70.00"); }
    @Test void withdrawal_shouldFail_whenBalanceIsInsufficient(){ Account a=account(); a.deposit(new BigDecimal("50.00")); assertThatThrownBy(()->a.withdraw(new BigDecimal("80.00"))).isInstanceOf(InsufficientBalanceException.class); assertThat(a.getBalance()).isEqualByComparingTo("50.00"); }
    @Test void movement_shouldFail_whenAmountIsZero(){ Account a=account(); assertThatThrownBy(()->a.deposit(new BigDecimal("0.00"))).isInstanceOf(IllegalArgumentException.class); assertThat(a.getBalance()).isEqualByComparingTo("0.00"); }
    @Test void movement_shouldFail_whenAmountIsNegative(){ Account a=account(); assertThatThrownBy(()->a.withdraw(new BigDecimal("-1.00"))).isInstanceOf(IllegalArgumentException.class); assertThat(a.getBalance()).isEqualByComparingTo("0.00"); }
    @Test void movement_shouldFail_whenAmountHasMoreThanTwoDecimals(){ Account a=account(); assertThatThrownBy(()->a.deposit(new BigDecimal("10.001"))).isInstanceOf(IllegalArgumentException.class); assertThat(a.getBalance()).isEqualByComparingTo("0.00"); }
    @Test void withdrawal_shouldFail_whenAccountIsBlocked(){ Account a=account(); a.deposit(new BigDecimal("100.00")); ReflectionTestUtils.setField(a,"status",AccountStatus.BLOCKED); assertThatThrownBy(()->a.withdraw(new BigDecimal("20.00"))).isInstanceOf(InvalidAccountStateException.class); assertThat(a.getBalance()).isEqualByComparingTo("100.00"); }
    @Test void deposit_shouldFail_whenAccountIsClosed(){ Account a=account(); ReflectionTestUtils.setField(a,"status",AccountStatus.CLOSED); assertThatThrownBy(()->a.deposit(new BigDecimal("20.00"))).isInstanceOf(InvalidAccountStateException.class); assertThat(a.getBalance()).isEqualByComparingTo("0.00"); }
    private Account account(){ return new Account(UUID.randomUUID(),"10000001"); }
}
