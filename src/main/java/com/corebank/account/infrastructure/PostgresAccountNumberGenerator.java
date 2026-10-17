package com.corebank.account.infrastructure;

import com.corebank.account.application.AccountNumberGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class PostgresAccountNumberGenerator implements AccountNumberGenerator {

    private final JdbcTemplate jdbcTemplate;

    public PostgresAccountNumberGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String nextNumber() {
        Long value = jdbcTemplate.queryForObject("SELECT nextval('account_number_seq')", Long.class);
        if (value == null) {
            throw new IllegalStateException("Account number sequence returned no value");
        }
        return String.format(Locale.ROOT, "%08d", value);
    }
}
