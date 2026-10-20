package com.corebank.transfer.application;

/**
 * Internal event emitted after transfer business processing.
 * Handled by observability listeners only after transaction commit.
 */
public record TransferCommittedEvent() {
}
