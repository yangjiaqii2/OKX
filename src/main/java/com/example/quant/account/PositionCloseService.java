package com.example.quant.account;

import com.example.quant.account.dto.PositionSummary;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.common.PageResult;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.order.OrderExecutionResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class PositionCloseService {
    private final PositionSnapshotService positionSnapshotService;
    private final OkxTradeAdapter okxTradeAdapter;
    private final ClosePositionRecordRepository closePositionRecordRepository;

    public PositionCloseService(PositionSnapshotService positionSnapshotService, OkxTradeAdapter okxTradeAdapter) {
        this(positionSnapshotService, okxTradeAdapter, null);
    }

    @Autowired
    public PositionCloseService(PositionSnapshotService positionSnapshotService, OkxTradeAdapter okxTradeAdapter,
                                ClosePositionRecordRepository closePositionRecordRepository) {
        this.positionSnapshotService = positionSnapshotService;
        this.okxTradeAdapter = okxTradeAdapter;
        this.closePositionRecordRepository = closePositionRecordRepository;
    }

    public OrderExecutionResult closePosition(String instId, String posSide, String marginMode) {
        if (!hasText(instId)) {
            throw new IllegalArgumentException("平仓合约不能为空");
        }
        PositionSummary position = positionSnapshotService.positions().stream()
                .filter(item -> instId.equalsIgnoreCase(item.instId()))
                .filter(item -> !hasText(posSide) || posSide.equalsIgnoreCase(item.posSide()))
                .filter(item -> decimal(item.size()).signum() > 0)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前没有可平仓持仓：" + instId));
        String closePosSide = hasText(posSide) ? posSide : position.posSide();
        String closeMarginMode = hasText(marginMode) ? marginMode : position.marginMode();
        if (!hasText(closeMarginMode)) {
            closeMarginMode = "cross";
        }
        OrderExecutionResult result = okxTradeAdapter.closePosition(position.instId(), closePosSide, closeMarginMode);
        recordCloseSubmitted(position, closePosSide, closeMarginMode, result);
        return result;
    }

    public PageResult<ClosePositionRecordView> closeRecords(int page, int size) {
        if (closePositionRecordRepository == null) {
            return new PageResult<>(List.of(), 0, Math.max(0, page), Math.max(1, size));
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        String username = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        List<ClosePositionRecordView> rows = closePositionRecordRepository
                .findByUserNameOrderByCreatedAtDesc(username, PageRequest.of(safePage, safeSize))
                .stream()
                .map(ClosePositionRecordView::from)
                .toList();
        return new PageResult<>(rows, rows.size(), safePage, safeSize);
    }

    private void recordCloseSubmitted(PositionSummary position, String posSide, String marginMode,
                                      OrderExecutionResult result) {
        if (closePositionRecordRepository == null) {
            return;
        }
        Instant now = Instant.now();
        ClosePositionRecordEntity record = new ClosePositionRecordEntity();
        record.setUserName(AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER));
        record.setInstId(position.instId());
        record.setPosSide(posSide);
        record.setMarginMode(marginMode);
        record.setCloseOrderId(result.externalOrderId());
        record.setCloseClOrdId("CLOSE" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        record.setSize(decimal(position.size()));
        record.setAvgPx(position.avgPrice());
        record.setStatus("CLOSE_SUBMITTED");
        record.setSource("MANUAL");
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        closePositionRecordRepository.save(record);
    }

    private static BigDecimal decimal(String value) {
        if (!hasText(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value).abs();
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
