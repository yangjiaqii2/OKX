package com.example.quant.ledger;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class AccountBillLedgerService {
    private final OkxRestClient restClient;
    private final AccountBillRepository repository;
    private AccountLedgerSyncStateRepository syncStates;

    public AccountBillLedgerService(OkxRestClient restClient, AccountBillRepository repository) {
        this.restClient = restClient;
        this.repository = repository;
    }

    @Autowired(required = false)
    void configureSyncStates(AccountLedgerSyncStateRepository syncStates) {
        this.syncStates = syncStates;
    }

    public SyncResult syncRecent() {
        String owner = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        int saved = 0;
        try {
            JsonNode data = restClient.privateGet("/api/v5/account/bills-archive?instType=SWAP&limit=100").path("data");
            if (!data.isArray()) {
                throw new IllegalStateException("OKX_BILLS_DATA_INVALID");
            }
            for (JsonNode item : data) {
                String billId = text(item, "billId");
                Instant billTime = time(text(item, "ts"));
                if (billId.isBlank() || billTime == null
                        || repository.findByUserNameAndBillId(owner, billId).isPresent()) {
                    continue;
                }
                AccountBillEntity row = new AccountBillEntity();
                row.setUserName(owner);
                row.setBillId(billId);
                row.setType(text(item, "type"));
                row.setSubType(text(item, "subType"));
                row.setCategory(category(text(item, "type"), text(item, "subType")));
                row.setCurrency(text(item, "ccy"));
                row.setInstId(text(item, "instId"));
                row.setOrdId(text(item, "ordId"));
                row.setClOrdId(text(item, "clOrdId"));
                row.setTradeId(text(item, "tradeId"));
                row.setPnl(decimal(item, "pnl"));
                row.setFee(decimal(item, "fee"));
                row.setBalanceChange(decimal(item, "balChg"));
                row.setBillTime(billTime);
                try {
                    repository.saveAndFlush(row);
                    saved++;
                } catch (DataIntegrityViolationException ignored) {
                    // Database unique key absorbs duplicate pages and duplicate runtime syncs.
                }
            }
            saveState(owner, "RECENT_WINDOW_SYNCED", null);
            return new SyncResult("RECENT_WINDOW_SYNCED", saved, null);
        } catch (RuntimeException ex) {
            try {
                saveState(owner, "FAILED", compact(ex.getMessage()));
            } catch (RuntimeException ignored) {
                // The failed return value is authoritative when the state store itself is unavailable.
            }
            return new SyncResult("FAILED", saved, compact(ex.getMessage()));
        }
    }

    private void saveState(String owner, String status, String error) {
        if (syncStates == null) {
            return;
        }
        AccountLedgerSyncStateEntity row = syncStates.findById(owner).orElseGet(AccountLedgerSyncStateEntity::new);
        row.setUserName(owner);
        row.setStatus(status);
        row.setSyncedAt(Instant.now());
        row.setErrorMessage(error);
        syncStates.save(row);
    }

    private static String category(String type, String subType) {
        if ("8".equals(type) && ("173".equals(subType) || "174".equals(subType))) {
            return "FUNDING";
        }
        return "2".equals(type) ? "TRADE" : "OTHER";
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        try {
            String value = text(node, field);
            return value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("INVALID_OKX_BILL_DECIMAL:" + field);
        }
    }

    private static Instant time(String value) {
        try {
            return value.isBlank() ? null : Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.substring(0, Math.min(240, compact.length()));
    }

    public record SyncResult(String status, int saved, String errorMessage) {
        public boolean ready() { return "RECENT_WINDOW_SYNCED".equals(status); }
    }
}
