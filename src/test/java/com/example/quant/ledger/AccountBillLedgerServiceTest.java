package com.example.quant.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.auth.AuthUserContext;
import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountBillLedgerServiceTest {
    @Test
    void persistsRawBillTypesAndFundingSemanticsWithoutMixingTradeFees() throws Exception {
        JsonNode response = new ObjectMapper().readTree("""
                {"code":"0","data":[
                  {"billId":"bill-funding","type":"8","subType":"173","ccy":"USDT",
                   "instId":"BTC-USDT-SWAP","pnl":"-1.20","fee":"0","balChg":"-1.20","ts":"1780000000000"},
                  {"billId":"bill-trade","type":"2","subType":"5","ccy":"USDT","instId":"BTC-USDT-SWAP",
                   "ordId":"ord-1","tradeId":"trade-1","clOrdId":"AUTO1","pnl":"10","fee":"-0.10",
                   "balChg":"9.90","ts":"1780000001000"}
                ]}
                """);
        AccountBillRepository repository = mock(AccountBillRepository.class);
        when(repository.findByUserNameAndBillId(any(), any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AccountBillLedgerService service = new AccountBillLedgerService(new FixedRestClient(response), repository);

        AccountBillLedgerService.SyncResult result = AuthUserContext.callAs("alice", service::syncRecent);

        assertThat(result.status()).isEqualTo("RECENT_WINDOW_SYNCED");
        assertThat(result.saved()).isEqualTo(2);
        ArgumentCaptor<AccountBillEntity> rows = ArgumentCaptor.forClass(AccountBillEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).saveAndFlush(rows.capture());
        assertThat(rows.getAllValues()).extracting(AccountBillEntity::getCategory)
                .containsExactly("FUNDING", "TRADE");
        assertThat(rows.getAllValues().get(0).getPnl()).isEqualByComparingTo("-1.20");
        assertThat(rows.getAllValues().get(1).getFee()).isEqualByComparingTo("-0.10");
    }

    @Test
    void failsClosedWhenLedgerSyncStateCannotBePersisted() throws Exception {
        JsonNode response = new ObjectMapper().readTree("{\"code\":\"0\",\"data\":[]}");
        AccountBillLedgerService service = new AccountBillLedgerService(
                new FixedRestClient(response), mock(AccountBillRepository.class));
        AccountLedgerSyncStateRepository states = mock(AccountLedgerSyncStateRepository.class);
        when(states.findById("alice")).thenReturn(Optional.empty());
        when(states.save(any())).thenThrow(new IllegalStateException("db unavailable"));
        service.configureSyncStates(states);

        AccountBillLedgerService.SyncResult result = AuthUserContext.callAs("alice", service::syncRecent);

        assertThat(result.ready()).isFalse();
        assertThat(result.status()).isEqualTo("FAILED");
    }

    private static final class FixedRestClient extends OkxRestClient {
        private final JsonNode response;
        private FixedRestClient(JsonNode response) {
            super(null, null, null, null);
            this.response = response;
        }
        @Override
        public JsonNode privateGet(String path) {
            assertThat(path).isEqualTo("/api/v5/account/bills-archive?instType=SWAP&limit=100");
            return response;
        }
    }
}
