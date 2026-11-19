package com.example.quant.account;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.quant.config.TradingMode;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.config.TradingProperties;
import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AccountSnapshotServiceTest {
    @Test
    void labelsDemoBalanceAsDemoRatherThanLive() throws Exception {
        JsonNode response = new ObjectMapper().readTree(
                "{\"code\":\"0\",\"data\":[{\"totalEq\":\"100\",\"availEq\":\"80\"}]}");
        AccountSnapshotService service = new AccountSnapshotService(new FixedRestClient(response));
        service.configureTrading(new TradingProperties(TradingMode.DEMO, true, 120, false));

        assertThat(service.summary().mode()).isEqualTo("OKX_DEMO");
        assertThat(service.verifyOkx().ok()).isTrue();
    }

    @Test
    void logsParsedBalanceFieldsAndSourceWithoutRawResponse() throws Exception {
        JsonNode response = new ObjectMapper().readTree(
                "{\"code\":\"0\",\"data\":[{\"totalEq\":\"100\",\"availEq\":\"80\",\"details\":[{\"ccy\":\"USDT\",\"eq\":\"100\",\"availBal\":\"80\"}]}]}");
        Logger logger = (Logger) LoggerFactory.getLogger(AccountSnapshotService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AccountSnapshotService service = new AccountSnapshotService(new FixedRestClient(response));

            assertThat(service.summary().availableBalance()).isEqualByComparingTo("80");

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(message -> message.contains("OKX account balance parsed")
                    && message.contains("availableSource=details.USDT.availBal")
                    && message.contains("equity=100")
                    && message.contains("availableBalance=80"));
            assertThat(messages).noneMatch(message -> message.contains("rawResponse=")
                    || message.contains("secret"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void logsEmptyAccountResponseAsDistinctDiagnostic() throws Exception {
        JsonNode response = new ObjectMapper().readTree("{\"code\":\"0\",\"data\":[]}");
        Logger logger = (Logger) LoggerFactory.getLogger(AccountSnapshotService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new AccountSnapshotService(new FixedRestClient(response)).summary();

            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                    .anyMatch(message -> message.contains("OKX account balance unavailable")
                            && message.contains("reason=EMPTY_RESPONSE"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void logsAccountApiFailureAsDistinctDiagnostic() {
        Logger logger = (Logger) LoggerFactory.getLogger(AccountSnapshotService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new AccountSnapshotService(new FailingRestClient()).summary();

            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                    .anyMatch(message -> message.contains("OKX account balance unavailable")
                            && message.contains("reason=API_ERROR"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void rejectsMalformedBalanceInsteadOfReportingSuccessfulZeroBalances() throws Exception {
        JsonNode response = new ObjectMapper().readTree("""
                {"code":"0","data":[{"totalEq":"invalid","availEq":"80"}]}
                """);
        AccountSnapshotService service = new AccountSnapshotService(new FixedRestClient(response));
        service.configureTrading(new TradingProperties(TradingMode.DEMO, true, 120, false));

        assertThat(service.summary().mode()).isEqualTo("OKX_ERROR");
        assertThat(service.verifyOkx().ok()).isFalse();
    }

    @Test
    void preservesExplicitZeroWithPositiveCashBalance() throws Exception {
        AccountSummary summary = summaryFor("""
                {"totalEq":"100","availEq":"0","details":[
                  {"ccy":"USDT","availEq":"0","availBal":"0","cashBal":"100","eq":"100"}
                ]}
                """);

        assertThat(summary.mode()).isEqualTo("OKX_REAL");
        assertThat(summary.equity()).isEqualByComparingTo("100");
        assertThat(summary.availableBalance()).isZero();
    }

    @Test
    void preservesUsdtAvailableEquityEvenWhenOtherAvailableAmountsArePositive() throws Exception {
        AccountSummary summary = summaryFor("""
                {"totalEq":"200","availEq":"150","details":[
                  {"ccy":"USDT","availEq":"0","availBal":"90","eq":"100"}
                ]}
                """);

        assertThat(summary.availableBalance()).isZero();
    }

    @Test
    void zeroAccountAvailableEquityBlocksPositiveUsdtAvailableBalance() throws Exception {
        AccountSummary summary = summaryFor("""
                {"totalEq":"200","availEq":"0","details":[{"ccy":"USDT","availBal":"90"}]}
                """);

        assertThat(summary.availableBalance()).isZero();
    }

    @Test
    void fallsBackOnlyToUsdtAvailableBalanceWhenItsAvailableEquityIsBlank() throws Exception {
        AccountSummary summary = summaryFor("""
                {"totalEq":"200","availEq":"150","details":[
                  {"ccy":"BTC","availEq":"2"}, {"ccy":"USDT","availEq":"","availBal":"18.50"}
                ]}
                """);

        assertThat(summary.availableBalance()).isEqualByComparingTo("18.50");
    }

    @Test
    void reportsUnavailableWhenOnlyAggregateAndCashAmountsArePresent() throws Exception {
        AccountSummary summary = summaryFor("""
                {"totalEq":"200","availEq":"150","details":[
                  {"ccy":"BTC","availEq":"2"}, {"ccy":"USDT","availEq":null,"cashBal":"90","eq":"100"}
                ]}
                """);

        assertThat(summary.mode()).isEqualTo("OKX_REAL");
        assertThat(summary.equity()).isEqualByComparingTo("200");
        assertThat(summary.availableBalance()).isNull();
    }

    @Test
    void rejectsMalformedUsdtAvailableEquityEvenWhenFallbackBalanceIsPositive() throws Exception {
        AccountSummary summary = summaryFor("""
                {"totalEq":"200","details":[{"ccy":"USDT","availEq":{},"availBal":"90"}]}
                """);

        assertThat(summary.mode()).isEqualTo("OKX_ERROR");
        assertThat(summary.availableBalance()).isNull();
    }

    private static AccountSummary summaryFor(String account) throws Exception {
        JsonNode response = new ObjectMapper().readTree("{\"code\":\"0\",\"data\":[" + account + "]}");
        return new AccountSnapshotService(new FixedRestClient(response)).summary();
    }

    private static final class FixedRestClient extends OkxRestClient {
        private final JsonNode response;

        private FixedRestClient(JsonNode response) {
            super(null, null, null, null);
            this.response = response;
        }

        @Override
        public JsonNode privateGet(String path) {
            assertThat(path).isEqualTo("/api/v5/account/balance");
            return response;
        }
    }

    private static final class FailingRestClient extends OkxRestClient {
        private FailingRestClient() {
            super(null, null, null, null);
        }

        @Override
        public JsonNode privateGet(String path) {
            throw new IllegalStateException("OKX code=50101, msg=invalid account");
        }

        @Override
        public boolean hasCredentials() {
            return true;
        }
    }
}
