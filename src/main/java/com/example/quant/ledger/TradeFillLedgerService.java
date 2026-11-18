package com.example.quant.ledger;

import com.example.quant.account.ClosePositionRecordEntity;
import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.okxtrade.OkxCurrentOrderService;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class TradeFillLedgerService {
    private final TradeFillRepository repository;

    public TradeFillLedgerService(TradeFillRepository repository) {
        this.repository = repository;
    }

    public void record(TradeOrderEntity order, OkxCurrentOrderService.Fill fill) {
        if (order == null || fill == null || blank(fill.tradeId())) {
            return;
        }
        if (repository.findByUserNameAndInstIdAndTradeId(
                order.getUserName(), order.getInstId(), fill.tradeId()).isPresent()) {
            return;
        }
        TradeFillEntity row = new TradeFillEntity();
        row.setUserName(order.getUserName());
        row.setTradePlanId(order.getTradePlanId());
        row.setPendingOrderId(order.getPendingOrderId());
        row.setInstId(order.getInstId());
        row.setOrdId(order.getOkxOrdId());
        row.setClOrdId(order.getClOrdId());
        row.setTradeId(fill.tradeId());
        row.setSide(fill.side());
        row.setPosSide(fill.posSide());
        row.setFillPrice(fill.fillPrice());
        row.setFillSize(fill.fillSize());
        row.setFee(fill.fee());
        row.setFeeCurrency(fill.feeCurrency());
        row.setFillPnl(fill.fillPnl());
        row.setFillTime(fill.timestamp() == null ? Instant.now() : fill.timestamp());
        save(row);
    }

    public void record(ClosePositionRecordEntity close, String ordId, OkxCurrentOrderService.Fill fill) {
        if (close == null || fill == null || blank(fill.tradeId()) || blank(ordId)) {
            return;
        }
        if (repository.findByUserNameAndInstIdAndTradeId(
                close.getUserName(), close.getInstId(), fill.tradeId()).isPresent()) {
            return;
        }
        TradeFillEntity row = new TradeFillEntity();
        row.setUserName(close.getUserName());
        row.setPendingOrderId(close.getPendingOrderId());
        row.setInstId(close.getInstId());
        row.setOrdId(ordId);
        row.setClOrdId(close.getCloseClOrdId());
        row.setTradeId(fill.tradeId());
        row.setSide(fill.side());
        row.setPosSide(fill.posSide());
        row.setFillPrice(fill.fillPrice());
        row.setFillSize(fill.fillSize());
        row.setFee(fill.fee());
        row.setFeeCurrency(fill.feeCurrency());
        row.setFillPnl(fill.fillPnl());
        row.setFillTime(fill.timestamp() == null ? Instant.now() : fill.timestamp());
        save(row);
    }

    public List<TradeFillEntity> fillsForPendingOrder(String userName, String pendingOrderId) {
        if (blank(userName) || blank(pendingOrderId)) {
            return List.of();
        }
        List<TradeFillEntity> rows = repository.findByUserNameAndPendingOrderId(userName, pendingOrderId);
        return rows == null ? List.of() : rows;
    }

    private void save(TradeFillEntity row) {
        try {
            repository.saveAndFlush(row);
        } catch (DataIntegrityViolationException ignored) {
            // Database unique key is the final duplicate-event guard.
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
