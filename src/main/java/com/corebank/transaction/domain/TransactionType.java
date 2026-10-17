package com.corebank.transaction.domain;

public enum TransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER_OUT,
    TRANSFER_IN;

    public boolean isTransfer() {
        return this == TRANSFER_OUT || this == TRANSFER_IN;
    }
}
