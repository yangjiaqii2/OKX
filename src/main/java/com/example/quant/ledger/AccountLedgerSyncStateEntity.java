package com.example.quant.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "account_ledger_sync_state")
public class AccountLedgerSyncStateEntity {
    @Id
    @Column(name = "user_name", length = 128)
    private String userName;
    @Column(name = "status", nullable = false, length = 32)
    private String status;
    @Column(name = "synced_at")
    private Instant syncedAt;
    @Column(name = "error_message", length = 240)
    private String errorMessage;

    public String getStatus() { return status; }
    public void setUserName(String value) { userName = value; }
    public void setStatus(String value) { status = value; }
    public void setSyncedAt(Instant value) { syncedAt = value; }
    public void setErrorMessage(String value) { errorMessage = value; }
}
