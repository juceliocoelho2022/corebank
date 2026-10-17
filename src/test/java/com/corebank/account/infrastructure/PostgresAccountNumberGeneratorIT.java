package com.corebank.account.infrastructure;

import com.corebank.account.application.AccountNumberGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresAccountNumberGeneratorIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    AccountNumberGenerator generator;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSequence() {
        jdbcTemplate.execute("ALTER SEQUENCE account_number_seq RESTART WITH 10000001");
    }

    @Test
    void generatesSequentialEightDigitAccountNumbers() {
        assertThat(generator.nextNumber()).isEqualTo("10000001");
        assertThat(generator.nextNumber()).isEqualTo("10000002");
    }

    @Test
    void generatedNumbersAreUnique() {
        Set<String> numbers = new HashSet<>();
        for (int index = 0; index < 100; index++) {
            String number = generator.nextNumber();
            assertThat(number).hasSize(8);
            assertThat(numbers.add(number)).isTrue();
        }
    }
}
