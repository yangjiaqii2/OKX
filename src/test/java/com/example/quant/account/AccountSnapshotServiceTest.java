package com.example.quant.account;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.quant.config.TradingMode;
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
                    && message.contains("availableSource=top.availEq")
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
