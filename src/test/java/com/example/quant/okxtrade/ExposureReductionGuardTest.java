package com.example.quant.okxtrade;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingMode;
import com.example.quant.config.TradingProperties;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExposureReductionGuardTest {

    @Test
    void allowsVerifiedDegradedCloseForManagedDemoPosition() {
        ExposureReductionGuard guard = guard(TradingMode.DEMO, managed("long", "5", "OKX_DEMO"));

        assertThatCode(() -> AuthUserContext.runAs("alice", () -> guard.verify(
                TradingOperationContext.of(TradingOperationType.CLOSE_POSITION, "alice", "pending-1", "plan-1"),
                closePayload("long"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCloseWhenExchangePositionDoesNotExist() {
        ExposureReductionGuard guard = guard(TradingMode.DEMO);

        assertRejected(guard, closePayload("long"), "POSITION_NOT_CONFIRMED");
    }

    @Test
    void rejectsReductionForWrongDirection() {
        ExposureReductionGuard guard = guard(TradingMode.DEMO, managed("long", "5", "OKX_DEMO"));
        Map<String, String> payload = algoPayload("buy", "long", "5");

        assertRejected(guard, payload, "REDUCTION_SIDE_MISMATCH", TradingOperationType.PLACE_STOP_LOSS);
    }

    @Test
    void rejectsReductionLargerThanRealExchangePosition() {
        ExposureReductionGuard guard = guard(TradingMode.DEMO, managed("long", "5", "OKX_DEMO"));
        Map<String, String> payload = algoPayload("sell", "long", "6");

        assertRejected(guard, payload, "REDUCTION_SIZE_EXCEEDS_POSITION", TradingOperationType.PLACE_STOP_LOSS);
    }

    @Test
    void rejectsDemoLiveEnvironmentMismatch() {
        ExposureReductionGuard guard = guard(TradingMode.LIVE, managed("long", "5", "OKX_DEMO"));

        assertRejected(guard, closePayload("long"), "TRADING_MODE_MISMATCH");
    }

    @Test
    void rejectsUnmanagedPositionAndWrongOwner() {
        PositionSummary unmanaged = position("long", "5", "OKX_DEMO", "UNMANAGED");
        ExposureReductionGuard guard = guard(TradingMode.DEMO, unmanaged);

        assertRejected(guard, closePayload("long"), "POSITION_OWNERSHIP_UNCONFIRMED");
        assertThatThrownBy(() -> AuthUserContext.runAs("bob", () -> guard.verify(
                TradingOperationContext.of(TradingOperationType.CLOSE_POSITION, "alice", "pending-1", "plan-1"),
                closePayload("long"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OWNER_MISMATCH");
    }

    @Test
    void rejectsAlgoWithoutReduceOnlyOrStableClientId() {
        ExposureReductionGuard guard = guard(TradingMode.DEMO, managed("long", "5", "OKX_DEMO"));
        Map<String, String> noReduceOnly = algoPayload("sell", "long", "5");
        noReduceOnly.put("reduceOnly", "false");
        Map<String, String> noClientId = algoPayload("sell", "long", "5");
        noClientId.remove("algoClOrdId");

        assertRejected(guard, noReduceOnly, "REDUCE_ONLY_REQUIRED", TradingOperationType.PLACE_STOP_LOSS);
        assertRejected(guard, noClientId, "IDEMPOTENCY_KEY_REQUIRED", TradingOperationType.PLACE_STOP_LOSS);
    }

    @Test
    void rejectsWhenRestSnapshotCannotConfirmPosition() {
        PositionSnapshotService positions = mock(PositionSnapshotService.class);
        when(positions.positions()).thenThrow(new IllegalStateException("timeout"));
        ExposureReductionGuard guard = new ExposureReductionGuard(
                positions, new TradingProperties(TradingMode.DEMO, true, 120, false));

        assertRejected(guard, closePayload("long"), "POSITION_SNAPSHOT_UNAVAILABLE");
    }

    @Test
    void validatesNetModeDirectionFromSignedPositionSize() {
        ExposureReductionGuard longGuard = guard(TradingMode.DEMO, managed("net", "5", "OKX_DEMO"));
        ExposureReductionGuard shortGuard = guard(TradingMode.DEMO, managed("net", "-5", "OKX_DEMO"));
        Map<String, String> longReduction = algoPayload("sell", "net", "5");
        longReduction.put("clOrdId", "REDUCElong");
        Map<String, String> shortReduction = algoPayload("buy", "net", "5");
        shortReduction.put("clOrdId", "REDUCEshort");

        assertThatCode(() -> verify(longGuard, longReduction, TradingOperationType.REDUCE_POSITION))
                .doesNotThrowAnyException();
        assertThatCode(() -> verify(shortGuard, shortReduction, TradingOperationType.REDUCE_POSITION))
                .doesNotThrowAnyException();
    }

    private static ExposureReductionGuard guard(TradingMode mode, PositionSummary... positions) {
        PositionSnapshotService snapshots = mock(PositionSnapshotService.class);
        when(snapshots.positions()).thenReturn(List.of(positions));
        return new ExposureReductionGuard(snapshots, new TradingProperties(mode, true, 120, false));
    }

    private static PositionSummary managed(String posSide, String size, String mode) {
        return position(posSide, size, mode, "MANAGED");
    }

    private static PositionSummary position(String posSide, String size, String mode, String ownership) {
        return new PositionSummary("BTC-USDT-SWAP", posSide, size, new BigDecimal("100"), BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.ONE, "cross", BigDecimal.TEN, BigDecimal.ZERO,
                new BigDecimal("500"), BigDecimal.ONE, mode, ownership, "MATCHED_LOCAL_FILL");
    }

    private static Map<String, String> closePayload(String posSide) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", "BTC-USDT-SWAP");
        payload.put("posSide", posSide);
        payload.put("mgnMode", "cross");
        payload.put("clOrdId", "MAXCstable");
        return payload;
    }

    private static Map<String, String> algoPayload(String side, String posSide, String size) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", "BTC-USDT-SWAP");
        payload.put("side", side);
        payload.put("posSide", posSide);
        payload.put("tdMode", "cross");
        payload.put("sz", size);
        payload.put("reduceOnly", "true");
        payload.put("algoClOrdId", "SLstable");
        payload.put("slTriggerPx", "95");
        return payload;
    }

    private static void assertRejected(ExposureReductionGuard guard, Map<String, String> payload, String reason) {
        assertRejected(guard, payload, reason, TradingOperationType.CLOSE_POSITION);
    }

    private static void assertRejected(ExposureReductionGuard guard, Map<String, String> payload, String reason,
                                       TradingOperationType operationType) {
        assertThatThrownBy(() -> verify(guard, payload, operationType))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason);
    }

    private static void verify(ExposureReductionGuard guard, Map<String, String> payload,
                               TradingOperationType operationType) {
        AuthUserContext.runAs("alice", () -> guard.verify(
                TradingOperationContext.of(operationType, "alice", "pending-1", "plan-1"), payload));
    }
}
