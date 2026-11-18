package com.example.quant.ledger;

import com.example.quant.common.TradingDayResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LedgerPnlService {
    private final TradingDayResolver tradingDayResolver;
    private final TradeFillRepository fills;
    private final AccountBillRepository bills;
    private AccountLedgerSyncStateRepository syncStates;

    public LedgerPnlService(TradeFillRepository fills, AccountBillRepository bills) {
        this.fills = fills;
        this.bills = bills;
        this.tradingDayResolver = new TradingDayResolver();
    }

    @Autowired(required = false)
    void configureSyncStates(AccountLedgerSyncStateRepository syncStates) {
        this.syncStates = syncStates;
    }

    public Summary summary(String owner) {
        boolean synced = syncStates != null && syncStates.findById(owner)
                .map(AccountLedgerSyncStateEntity::getStatus)
                .filter("RECENT_WINDOW_SYNCED"::equals)
                .isPresent();
        return summary(owner, synced);
    }

    /** Returns ledger facts belonging only to the supplied automatic-trade lifecycles. */
    public Summary summary(String owner, Set<String> pendingOrderIds) {
        boolean synced = syncStates != null && syncStates.findById(owner)
                .map(AccountLedgerSyncStateEntity::getStatus)
                .filter("RECENT_WINDOW_SYNCED"::equals)
                .isPresent();
        if (!synced) {
            return new Summary(null, null, null, null, null, "LEDGER_SYNC_REQUIRED");
        }
        if (pendingOrderIds == null || pendingOrderIds.isEmpty()) {
            return new Summary(null, null, null, null, null, "NO_AUTO_TRADE_LIFECYCLE");
        }
        List<TradeFillEntity> scopedFills = fills.findByUserName(owner).stream()
                .filter(fill -> pendingOrderIds.contains(fill.getPendingOrderId()))
                .filter(LedgerPnlService::traceable)
                .toList();
        Set<String> scopedOrderIds = scopedFills.stream().map(TradeFillEntity::getOrdId)
                .filter(LedgerPnlService::hasText).collect(Collectors.toSet());
        Set<String> scopedInstruments = scopedFills.stream().map(TradeFillEntity::getInstId)
                .filter(LedgerPnlService::hasText).collect(Collectors.toSet());
        List<AccountBillEntity> ownerBills = bills.findByUserName(owner);
        List<AccountBillEntity> scopedFunding = ownerBills.stream()
                .filter(bill -> "FUNDING".equals(bill.getCategory()))
                .filter(bill -> scopedOrderIds.contains(bill.getOrdId()))
                .toList();
        boolean unscopedFundingExists = ownerBills.stream()
                .filter(bill -> "FUNDING".equals(bill.getCategory()))
                .filter(bill -> scopedInstruments.contains(bill.getInstId()))
                .anyMatch(bill -> !scopedOrderIds.contains(bill.getOrdId()));
        BigDecimal gross = scopedFills.stream().map(TradeFillEntity::getFillPnl)
                .map(LedgerPnlService::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = scopedFills.stream().map(TradeFillEntity::getFee)
                .map(LedgerPnlService::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (unscopedFundingExists) {
            return new Summary(gross, fees, null, null, null,
                    "AUTO_SCOPED_LEDGER_FUNDING_UNATTRIBUTED");
        }
        BigDecimal funding = scopedFunding.stream().map(AccountBillEntity::getPnl)
                .map(LedgerPnlService::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate today = tradingDayResolver.tradingDate(Instant.now());
        BigDecimal todayGross = scopedFills.stream().filter(fill -> isToday(fill.getFillTime(), today))
                .map(TradeFillEntity::getFillPnl).map(LedgerPnlService::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal todayFees = scopedFills.stream().filter(fill -> isToday(fill.getFillTime(), today))
                .map(TradeFillEntity::getFee).map(LedgerPnlService::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal todayFunding = scopedFunding.stream().filter(bill -> isToday(bill.getBillTime(), today))
                .map(AccountBillEntity::getPnl).map(LedgerPnlService::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Summary(gross, fees, funding, gross.add(fees).add(funding),
                todayGross.add(todayFees).add(todayFunding), "AUTO_SCOPED_EXCHANGE_RECENT_LEDGER");
    }

    public Summary summary(String owner, boolean ledgerSynced) {
        if (!ledgerSynced) {
            return new Summary(null, null, null, null, null, "LEDGER_SYNC_REQUIRED");
        }
        List<TradeFillEntity> ownerFills = fills.findByUserName(owner).stream()
                .filter(LedgerPnlService::traceable)
                .toList();
        Set<String> managedInstruments = ownerFills.stream()
                .map(TradeFillEntity::getInstId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        List<AccountBillEntity> ownerBills = bills.findByUserName(owner);
        BigDecimal gross = ownerFills.stream()
                .map(TradeFillEntity::getFillPnl).map(LedgerPnlService::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = ownerFills.stream()
                .map(TradeFillEntity::getFee).map(LedgerPnlService::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal funding = ownerBills.stream()
                .filter(bill -> "FUNDING".equals(bill.getCategory()))
                .filter(bill -> managedInstruments.contains(bill.getInstId()))
                .map(AccountBillEntity::getPnl).map(LedgerPnlService::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate today = tradingDayResolver.tradingDate(Instant.now());
        BigDecimal todayGross = ownerFills.stream().filter(fill -> isToday(fill.getFillTime(), today))
                .map(TradeFillEntity::getFillPnl).map(LedgerPnlService::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal todayFees = ownerFills.stream().filter(fill -> isToday(fill.getFillTime(), today))
                .map(TradeFillEntity::getFee).map(LedgerPnlService::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal todayFunding = ownerBills.stream()
                .filter(bill -> "FUNDING".equals(bill.getCategory()))
                .filter(bill -> managedInstruments.contains(bill.getInstId()))
                .filter(bill -> isToday(bill.getBillTime(), today))
                .map(AccountBillEntity::getPnl).map(LedgerPnlService::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Summary(gross, fees, funding, gross.add(fees).add(funding),
                todayGross.add(todayFees).add(todayFunding), "EXCHANGE_RECENT_LEDGER");
    }

    private static boolean isToday(Instant timestamp, LocalDate today) {
        return timestamp != null && LocalDate.ofInstant(timestamp, TradingDayResolver.BUSINESS_ZONE).equals(today);
    }

    private static boolean traceable(TradeFillEntity fill) {
        return (fill.getTradePlanId() != null && !fill.getTradePlanId().isBlank())
                || (fill.getPendingOrderId() != null && !fill.getPendingOrderId().isBlank());
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Summary(BigDecimal grossRealizedPnl, BigDecimal tradingFees, BigDecimal funding,
                          BigDecimal netRealizedPnl, BigDecimal todayNetRealizedPnl, String dataQuality) {
    }
}
