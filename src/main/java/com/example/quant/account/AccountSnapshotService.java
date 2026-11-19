package com.example.quant.account;

import com.example.quant.account.dto.AccountSummary;
import com.example.quant.account.dto.OkxAccountVerificationResult;
import com.example.quant.config.TradingMode;
import com.example.quant.config.TradingProperties;
import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AccountSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(AccountSnapshotService.class);

    private final OkxRestClient okxRestClient;
    private TradingProperties trading;

    public AccountSnapshotService(OkxRestClient okxRestClient) {
        this.okxRestClient = okxRestClient;
    }

    @Autowired(required = false)
    void configureTrading(TradingProperties trading) {
        this.trading = trading;
    }

    public AccountSummary summary() {
        try {
            JsonNode data = okxRestClient.privateGet("/api/v5/account/balance").path("data");
            if (!data.isArray() || data.isEmpty()) {
                log.warn("OKX account balance unavailable reason=EMPTY_RESPONSE mode={} dataType={} dataCount={}",
                        accountMode(), data == null || data.isMissingNode() ? "MISSING" : data.getNodeType(),
                        data != null && data.isArray() ? data.size() : 0);
                return new AccountSummary(
                        BigDecimal.ZERO,
                        null,
                        "OKX_EMPTY",
                        "OKX returned no account balance data."
                );
            }
            JsonNode account = data.get(0);
            JsonNode usdt = usdtDetail(account);
            ParsedAmount available = availableUsdt(account, usdt);
            ParsedAmount equity = firstPositive(
                    new ParsedAmount("top.totalEq", reportedDecimal(account, "totalEq")),
                    new ParsedAmount("details.USDT.eq", reportedDecimal(usdt, "eq")),
                    new ParsedAmount("details.USDT.cashBal", reportedDecimal(usdt, "cashBal")),
                    available
            );
            String availableStatus = available.value() == null ? "UNAVAILABLE"
                    : available.value().signum() > 0 ? "AVAILABLE" : "INSUFFICIENT";
            log.info("OKX account balance parsed mode={} dataCount={} detailsCount={} equity={} equitySource={} availableBalance={} availableSource={} availableStatus={}",
                    accountMode(), data.size(), detailsCount(account), equity.value(), equity.source(),
                    available.value(), available.source(), availableStatus);
            return new AccountSummary(
                    equity.value(),
                    available.value(),
                    accountMode(),
                    "OKX account balance loaded; availableSource=" + available.source()
            );
        } catch (IllegalStateException ex) {
            String mode = okxRestClient.hasCredentials() ? "OKX_ERROR" : "OKX_UNBOUND";
            log.warn("OKX account balance unavailable reason=API_ERROR mode={} errorType={} message={}",
                    mode, ex.getClass().getSimpleName(), compact(ex.getMessage()));
            return new AccountSummary(BigDecimal.ZERO, null, mode, ex.getMessage());
        } catch (RuntimeException ex) {
            String mode = "OKX_ERROR";
            log.warn("OKX account balance unavailable reason=PARSER_ERROR mode={} errorType={} message={}",
                    mode, ex.getClass().getSimpleName(), compact(ex.getMessage()));
            return new AccountSummary(BigDecimal.ZERO, null, mode, "OKX 账户金额无法解析");
        }
    }

    public OkxAccountVerificationResult verifyOkx() {
        AccountSummary summary = summary();
        boolean ok = "OKX_REAL".equals(summary.mode()) || "OKX_DEMO".equals(summary.mode())
                || "OKX_EMPTY".equals(summary.mode());
        String message = ok
                ? "OKX接口验证通过，当前API Key可以读取账户信息。"
                : okxDiagnosticMessage(summary.message());
        return new OkxAccountVerificationResult(
                ok,
                summary.mode(),
                message,
                summary.equity(),
                summary.availableBalance()
        );
    }

    private String accountMode() {
        return trading != null && trading.mode() == TradingMode.DEMO ? "OKX_DEMO" : "OKX_REAL";
    }

    private static String okxDiagnosticMessage(String message) {
        String raw = message == null ? "" : message;
        if (raw.contains("50112") || raw.contains("Invalid OK-ACCESS-TIMESTAMP")) {
            return "OKX时间戳无效：后端已使用OKX服务器时间校准签名时间戳；如果仍失败，请检查当前机器能否访问OKX公共时间接口，以及系统时间是否严重偏差。原始信息：" + raw;
        }
        if (raw.contains("401")) {
            return "OKX返回401：请检查API Key、Secret、Passphrase是否匹配，API是否开启读取权限，以及IP白名单是否包含当前后端出口IP。原始信息：" + raw;
        }
        if (raw.contains("not bound")) {
            return "尚未绑定OKX API。";
        }
        return raw.isBlank() ? "OKX接口验证失败。" : raw;
    }

    private static ParsedAmount availableUsdt(JsonNode account, JsonNode usdt) {
        BigDecimal amount = reportedDecimal(usdt, "availEq");
        String source = "details.USDT.availEq";
        if (amount == null) {
            amount = reportedDecimal(usdt, "availBal");
            source = "details.USDT.availBal";
        }
        BigDecimal accountAvailableEquity = reportedDecimal(account, "availEq");
        // The account-wide field is in USD. It can rule out new margin when exhausted.
        if (amount != null && amount.signum() > 0
                && accountAvailableEquity != null && accountAvailableEquity.signum() <= 0) {
            return new ParsedAmount("top.availEq", BigDecimal.ZERO);
        }
        return new ParsedAmount(amount == null ? "NONE" : source, amount);
    }

    private static BigDecimal reportedDecimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || (value.isTextual() && value.textValue().isBlank())) {
            return null;
        }
        if (!value.isNumber() && !value.isTextual()) {
            throw new NumberFormatException("Invalid amount field: " + field);
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException ex) {
            throw new NumberFormatException("Invalid amount field: " + field);
        }
    }

    private static JsonNode usdtDetail(JsonNode account) {
        JsonNode details = account.path("details");
        if (!details.isArray()) {
            return MissingNode.getInstance();
        }
        for (JsonNode detail : details) {
            if ("USDT".equalsIgnoreCase(detail.path("ccy").asText(""))) {
                return detail;
            }
        }
        return MissingNode.getInstance();
    }

    private static ParsedAmount firstPositive(ParsedAmount... values) {
        for (ParsedAmount value : values) {
            if (value != null && value.value() != null && value.value().signum() > 0) {
                return value;
            }
        }
        return new ParsedAmount("NONE", BigDecimal.ZERO);
    }

    private static int detailsCount(JsonNode account) {
        JsonNode details = account.path("details");
        return details.isArray() ? details.size() : 0;
    }

    private static String compact(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > 180 ? normalized.substring(0, 180) + "..." : normalized;
    }

    private record ParsedAmount(String source, BigDecimal value) {
    }
}
