package com.example.quant.account;

import com.example.quant.account.dto.AccountSummary;
import com.example.quant.account.dto.OkxAccountVerificationResult;
import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class AccountSnapshotService {
    private final OkxRestClient okxRestClient;

    public AccountSnapshotService(OkxRestClient okxRestClient) {
        this.okxRestClient = okxRestClient;
    }

    public AccountSummary summary() {
        try {
            JsonNode data = okxRestClient.privateGet("/api/v5/account/balance").path("data");
            if (!data.isArray() || data.isEmpty()) {
                return new AccountSummary(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "OKX_EMPTY",
                        "OKX returned no account balance data."
                );
            }
            JsonNode account = data.get(0);
            return new AccountSummary(
                    decimal(account, "totalEq"),
                    decimal(account, "availEq"),
                    "OKX_REAL",
                    "OKX account balance loaded."
            );
        } catch (IllegalStateException ex) {
            String mode = okxRestClient.hasCredentials() ? "OKX_ERROR" : "OKX_UNBOUND";
            return new AccountSummary(BigDecimal.ZERO, BigDecimal.ZERO, mode, ex.getMessage());
        }
    }

    public OkxAccountVerificationResult verifyOkx() {
        AccountSummary summary = summary();
        boolean ok = "OKX_REAL".equals(summary.mode()) || "OKX_EMPTY".equals(summary.mode());
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

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = node.path(field).asText("0");
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }
}
