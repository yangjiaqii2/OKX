package com.example.quant.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountLedgerSyncStateRepository extends JpaRepository<AccountLedgerSyncStateEntity, String> {
}
