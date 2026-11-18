package com.example.quant.notification;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.ClosePositionRecordEntity;
import com.example.quant.account.ClosePositionRecordRepository;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.event.TradeEventEntity;
import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingMode;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.ledger.TradeFillRepository;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxInstrumentRulesProvider;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderEntity;
import com.example.quant.order.PendingOrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class TelegramMessageRenderer {
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(DISPLAY_ZONE);
    private static final List<String> ACTIVE_PROTECTION = List.of(
            "SUBMITTED", "PROTECTION_SUBMITTED", "OPEN", "ACTIVE");

    private final PendingOrderRepository pendingOrders;
    private final TradeOrderRepository tradeOrders;
    private final ClosePositionRecordRepository closes;
    private final TradeFillRepository fills;
    private final OkxInstrumentRulesProvider rules;
    private final AccountSnapshotService accounts;
    private final PositionSnapshotService positions;

    public TelegramMessageRenderer(PendingOrderRepository pendingOrders, TradeOrderRepository tradeOrders,
                                   ClosePositionRecordRepository closes, TradeFillRepository fills,
                                   OkxInstrumentRulesProvider rules, AccountSnapshotService accounts,
                                   PositionSnapshotService positions) {
        this.pendingOrders = pendingOrders;
        this.tradeOrders = tradeOrders;
        this.closes = closes;
        this.fills = fills;
        this.rules = rules;
        this.accounts = accounts;
        this.positions = positions;
    }

    public String render(TradeEventEntity event, TradingMode mode) {
        if (event == null) {
            return alert(null, mode, "交易通知异常", "交易事件不可用");
        }
        return switch (value(event.getEventType())) {
            case "PROTECTION_SUBMITTED" -> renderEntry(event, mode);
            case "CLOSE_CONFIRMED" -> renderClose(event, mode);
            case "SIDEWAYS_TP_REPLACED" -> renderProtectionAdjusted(event, mode);
            case "MAX_HOLD_CLOSE_SUBMITTED", "MANUAL_CLOSE_SUBMITTED" -> renderCloseSubmitted(event, mode);
            case "ENTRY_TIMEOUT_CANCELLED" -> alert(event, mode, "委托未成交，已撤销",
                    "入场委托没有成交，系统已撤销剩余委托");
            case "ENTRY_SUBMIT_UNKNOWN" -> alert(event, mode, "订单状态未知",
                    "OKX尚未确认提交结果，系统正在对账，请勿重复下单");
            case "PROTECTION_FAILED" -> alert(event, mode, "持仓保护异常",
                    "止盈止损尚未确认生效，请立即检查OKX当前仓位");
            case "RECOVERY_FAILED" -> alert(event, mode, "交易恢复异常",
                    "交易状态恢复未完成，自动交易可能已被阻断");
            default -> alert(event, mode, title(event.getEventType()), "请在交易终端检查当前状态");
        };
    }

    private String renderEntry(TradeEventEntity event, TradingMode mode) {
        Facts facts = facts(event);
        if (facts.order() == null) {
            return alert(event, mode, "开仓数据同步中", "成交已确认，但本地订单明细尚未完成对账");
        }
        List<TradeFillEntity> entryFills = entryFills(facts);
        BigDecimal fillSize = sumSize(entryFills);
        BigDecimal fillPrice = weightedPrice(entryFills);
        if (fillSize == null || fillSize.signum() <= 0) {
            TradeOrderEntity entry = facts.tradeOrders().stream().filter(row -> !row.isReduceOnly()).findFirst().orElse(null);
            fillSize = entry == null ? null : positive(entry.getFilledSize(), entry.getSize());
            fillPrice = entry == null ? null : positive(entry.getAverageFillPrice(), entry.getPrice());
        }
        PositionSummary position = matchingPosition(facts);
        BigDecimal leverage = position == null ? BigDecimal.valueOf(facts.order().leverage())
                : positive(position.leverage(), BigDecimal.valueOf(facts.order().leverage()));
        BigDecimal margin = position == null ? facts.order().marginAmount()
                : positive(position.margin(), position.initialMargin());
        BigDecimal baseSize = baseQuantity(facts.instrumentRules(), fillSize, fillPrice);
        BigDecimal notional = amount(baseSize, fillPrice);
        String title = value(event.getReasonCode()).contains("PARTIAL")
                ? "部分成交并已保护" : "开仓完成";

        StringBuilder body = new StringBuilder()
                .append("🟢 <b>").append(mode(mode)).append(" · ").append(title).append("</b>\n\n")
                .append("<b>").append(escape(event.getInstId())).append("</b>\n")
                .append("<pre>")
                .append(line("方向", direction(facts.order().posSide())))
                .append(line("成交价格", money(fillPrice)))
                .append(line("杠杆", leverage(leverage)))
                .append(line("成交数量", quantity(baseSize, fillSize, facts.instrumentRules())))
                .append(line("成交价值", money(notional)))
                .append(line(position == null ? "提交保证金" : "占用保证金", money(margin)))
                .append("\n")
                .append(protectionLines(facts.tradeOrders()))
                .append(line("计划最大亏损", money(facts.order().maxLossAmount())))
                .append("\n")
                .append(line("成交时间", time(lastTime(entryFills, event.getCreatedAt()))))
                .append(accountLines(account(event)))
                .append("</pre>");
        return body.toString();
    }

    private String renderClose(TradeEventEntity event, TradingMode mode) {
        Facts facts = facts(event);
        ClosePositionRecordEntity close = latestClose(facts);
        List<TradeFillEntity> entryFills = entryFills(facts);
        List<TradeFillEntity> closeFills = closeFills(facts);
        BigDecimal entryPrice = weightedPrice(entryFills);
        BigDecimal closePrice = positive(weightedPrice(closeFills),
                close != null && close.getRealizedPnl() != null && close.getFee() != null ? close.getAvgPx() : null);
        BigDecimal contractSize = close == null ? sumSize(closeFills)
                : positive(close.getSize(), sumSize(closeFills));
        BigDecimal baseSize = baseQuantity(facts.instrumentRules(), contractSize, closePrice);
        BigDecimal gross = close == null ? null : close.getRealizedPnl();
        BigDecimal fee = close == null ? null : close.getFee();
        BigDecimal funding = close == null ? null : close.getFundingFee();
        BigDecimal net = gross == null || fee == null || funding == null ? null : gross.add(fee).add(funding);
        BigDecimal margin = facts.order() == null ? null : facts.order().marginAmount();
        BigDecimal roe = net == null || margin == null || margin.signum() <= 0 ? null
                : net.multiply(BigDecimal.valueOf(100)).divide(margin, 4, RoundingMode.HALF_UP);
        Duration holding = holdingDuration(entryFills, closeFills);
        BigDecimal headline = net == null ? gross : net;
        String resultType = net == null ? "已确认毛" : "净";
        String resultWord = headline == null ? "盈亏同步中" : resultType + (headline.signum() >= 0 ? "盈利" : "亏损");
        String icon = headline == null ? "⚪" : headline.signum() >= 0 ? "🟢" : "🔴";

        return icon + " <b>" + mode(mode) + " · 平仓结算</b>\n"
                + icon + " <b>" + resultWord + (headline == null ? "" : " " + signedMoney(headline)) + "</b>\n\n"
                + "<b>" + escape(event.getInstId()) + "</b>\n"
                + "<pre>"
                + line("原持仓方向", direction(facts.order() == null ? event.getOldStatus() : facts.order().posSide()))
                + line("平仓原因", closeReason(facts, close))
                + line("开仓均价", money(entryPrice))
                + line("平仓均价", money(closePrice))
                + line("平仓数量", quantity(baseSize, contractSize, facts.instrumentRules()))
                + line("持仓时长", duration(holding))
                + "\n"
                + line("毛盈亏", signedMoney(gross))
                + line("交易手续费", signedMoney(fee))
                + line("资金费", signedMoney(funding))
                + line("净盈亏", signedMoney(net))
                + line("保证金收益率", percent(roe))
                + "\n"
                + accountLines(account(event))
                + line("结算时间", time(lastTime(closeFills, close == null ? event.getCreatedAt() : close.getUpdatedAt())))
                + "</pre>";
    }

    private String renderProtectionAdjusted(TradeEventEntity event, TradingMode mode) {
        Facts facts = facts(event);
        return "🟡 <b>" + mode(mode) + " · 止盈已调整</b>\n\n"
                + "<b>" + escape(event.getInstId()) + "</b>\n<pre>"
                + line("持仓方向", direction(facts.order() == null ? null : facts.order().posSide()))
                + protectionLines(facts.tradeOrders())
                + line("调整时间", time(event.getCreatedAt()))
                + "</pre>";
    }

    private String renderCloseSubmitted(TradeEventEntity event, TradingMode mode) {
        Facts facts = facts(event);
        ClosePositionRecordEntity close = latestClose(facts);
        return "🟡 <b>" + mode(mode) + " · 平仓处理中</b>\n\n"
                + "<b>" + escape(event.getInstId()) + "</b>\n<pre>"
                + line("原持仓方向", direction(facts.order() == null ? null : facts.order().posSide()))
                + line("平仓数量", contracts(close == null ? null : close.getSize()))
                + line("平仓原因", closeReason(facts, close))
                + line("当前状态", "等待OKX成交与账本对账")
                + line("提交时间", time(event.getCreatedAt()))
                + "</pre>";
    }

    private String alert(TradeEventEntity event, TradingMode mode, String title, String recommendation) {
        String instId = event == null ? null : event.getInstId();
        Instant createdAt = event == null ? null : event.getCreatedAt();
        String detail = event == null ? null : event.getReasonMessage();
        String state = event == null ? null : event.getNewStatus();
        return "⚠️ <b>" + mode(mode) + " · " + escape(title) + "</b>\n\n"
                + "<b>" + escape(instId) + "</b>\n<pre>"
                + line("处理建议", recommendation)
                + (hasText(state) ? line("当前状态", state) : "")
                + (hasText(detail) ? line("说明", detail) : "")
                + line("时间", time(createdAt))
                + "</pre>";
    }

    private Facts facts(TradeEventEntity event) {
        String owner = hasText(event.getUserName()) ? event.getUserName().trim() : OkxCredentialStore.SYSTEM_USER;
        PendingOrder order = null;
        if (pendingOrders != null && hasText(event.getPendingOrderId())) {
            PendingOrderEntity entity = pendingOrders.findById(event.getPendingOrderId()).orElse(null);
            if (entity != null && owner.equals(entity.getUserName())) {
                order = entity.toDomain();
            }
        }
        List<TradeOrderEntity> orderRows = tradeOrders == null || !hasText(event.getPendingOrderId())
                ? List.of()
                : tradeOrders.findByPendingOrderIdOrderByCreatedAtAsc(event.getPendingOrderId()).stream()
                        .filter(row -> owner.equals(row.getUserName())).toList();
        List<TradeFillEntity> fillRows = fills == null ? List.of()
                : fills.findByUserName(owner).stream()
                        .filter(row -> event.getPendingOrderId() != null
                                && event.getPendingOrderId().equals(row.getPendingOrderId()))
                        .sorted(Comparator.comparing(TradeFillEntity::getFillTime,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList();
        OkxInstrumentRules instrumentRules = null;
        try {
            instrumentRules = rules == null ? null : rules.rules(event.getInstId());
        } catch (RuntimeException ignored) {
            // Notification rendering must not fail or claim a guessed contract conversion.
        }
        return new Facts(owner, order, orderRows, fillRows, instrumentRules);
    }

    private ClosePositionRecordEntity latestClose(Facts facts) {
        if (closes == null || facts.order() == null) return null;
        List<ClosePositionRecordEntity> records = closes.findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(
                facts.owner(), facts.order().id().toString());
        return records == null || records.isEmpty() ? null : records.get(0);
    }

    private PositionSummary matchingPosition(Facts facts) {
        if (positions == null || facts.order() == null) return null;
        try {
            return AuthUserContext.callAs(facts.owner(), () -> positions.positions().stream()
                    .filter(row -> facts.order().instId().equalsIgnoreCase(row.instId()))
                    .filter(row -> !hasText(facts.order().posSide())
                            || facts.order().posSide().equalsIgnoreCase(row.posSide()))
                    .findFirst().orElse(null));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private AccountSummary account(TradeEventEntity event) {
        if (accounts == null) return null;
        String owner = hasText(event.getUserName()) ? event.getUserName().trim() : OkxCredentialStore.SYSTEM_USER;
        try {
            return AuthUserContext.callAs(owner, accounts::summary);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<TradeFillEntity> entryFills(Facts facts) {
        if (facts.order() == null) return List.of();
        return facts.fills().stream().filter(row -> same(row.getSide(), facts.order().side())).toList();
    }

    private static List<TradeFillEntity> closeFills(Facts facts) {
        if (facts.order() == null) return List.of();
        return facts.fills().stream().filter(row -> !same(row.getSide(), facts.order().side())).toList();
    }

    private static String protectionLines(List<TradeOrderEntity> rows) {
        List<TradeOrderEntity> active = rows.stream()
                .filter(TradeOrderEntity::isReduceOnly)
                .filter(row -> ACTIVE_PROTECTION.contains(value(row.getStatus()).toUpperCase(Locale.ROOT)))
                .toList();
        StringBuilder lines = new StringBuilder();
        TradeOrderEntity stop = active.stream().filter(row -> value(row.getOrderRole()).contains("STOP_LOSS"))
                .findFirst().orElse(null);
        lines.append(line("止损价格", money(stop == null ? null : stop.getPrice())));
        List<TradeOrderEntity> takeProfits = active.stream()
                .filter(row -> isTakeProfitRole(row.getOrderRole()))
                .toList();
        if (takeProfits.isEmpty()) {
            lines.append(line("止盈价格", "同步中"));
        } else {
            for (int i = 0; i < takeProfits.size(); i++) {
                TradeOrderEntity takeProfit = takeProfits.get(i);
                lines.append(line(takeProfits.size() == 1 ? "止盈价格" : "止盈" + (i + 1),
                        money(takeProfit.getPrice()) + " · " + contracts(takeProfit.getSize())));
            }
        }
        return lines.toString();
    }

    private static BigDecimal baseQuantity(OkxInstrumentRules rules, BigDecimal contracts, BigDecimal price) {
        if (rules == null || contracts == null || contracts.signum() <= 0) return null;
        String ccy = value(rules.contractValueCurrency()).toUpperCase(Locale.ROOT);
        if ("USDT".equals(ccy) || "USD".equals(ccy) || "USDC".equals(ccy)) {
            return price == null || price.signum() <= 0 ? null
                    : contracts.multiply(rules.contractValue()).divide(price, 16, RoundingMode.DOWN);
        }
        return contracts.multiply(rules.contractValue());
    }

    private static BigDecimal weightedPrice(List<TradeFillEntity> rows) {
        BigDecimal size = sumSize(rows);
        if (size == null || size.signum() <= 0) return null;
        BigDecimal total = rows.stream()
                .filter(row -> row.getFillPrice() != null && row.getFillSize() != null)
                .map(row -> row.getFillPrice().multiply(row.getFillSize()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.signum() == 0 ? null : total.divide(size, 12, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static BigDecimal sumSize(List<TradeFillEntity> rows) {
        if (rows == null || rows.isEmpty()) return null;
        BigDecimal size = rows.stream().map(TradeFillEntity::getFillSize).filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return size.signum() <= 0 ? null : size;
    }

    private static Duration holdingDuration(List<TradeFillEntity> entry, List<TradeFillEntity> close) {
        Instant start = entry.stream().map(TradeFillEntity::getFillTime).filter(value -> value != null).min(Instant::compareTo)
                .orElse(null);
        Instant end = close.stream().map(TradeFillEntity::getFillTime).filter(value -> value != null).max(Instant::compareTo)
                .orElse(null);
        return start == null || end == null || end.isBefore(start) ? null : Duration.between(start, end);
    }

    private static Instant lastTime(List<TradeFillEntity> rows, Instant fallback) {
        return rows.stream().map(TradeFillEntity::getFillTime).filter(value -> value != null).max(Instant::compareTo)
                .orElse(fallback);
    }

    private static String accountLines(AccountSummary account) {
        if (account == null || !("OKX_REAL".equals(account.mode()) || "OKX_DEMO".equals(account.mode()))) {
            return line("可用金额", "同步中") + line("总资产", "同步中");
        }
        return line("可用金额", money(account.availableBalance())) + line("总资产", money(account.equity()));
    }

    private static String closeReason(Facts facts, ClosePositionRecordEntity close) {
        TradeOrderEntity triggered = facts.tradeOrders().stream()
                .filter(TradeOrderEntity::isReduceOnly)
                .filter(row -> "ALGO_EFFECTIVE".equals(row.getStatus()) || "FILLED".equals(row.getStatus()))
                .findFirst().orElse(null);
        if (triggered != null && value(triggered.getOrderRole()).contains("STOP_LOSS")) return "止损触发";
        if (triggered != null && isTakeProfitRole(triggered.getOrderRole())) return "止盈触发";
        if (close == null) return "同步中";
        return switch (value(close.getSource()).toUpperCase(Locale.ROOT)) {
            case "MANUAL" -> "手动平仓";
            case "MAX_HOLD_TIMEOUT" -> "达到最大持仓时间";
            case "POSITION_GONE" -> "OKX仓位已关闭";
            default -> hasText(close.getSource()) ? close.getSource() : "同步中";
        };
    }

    private static String quantity(BigDecimal baseSize, BigDecimal contracts, OkxInstrumentRules rules) {
        if (baseSize == null) return contracts(contracts);
        String currency = rules == null ? "币" : value(rules.contractValueCurrency());
        return number(baseSize) + " " + currency + "（" + number(contracts) + " contracts）";
    }

    private static String contracts(BigDecimal contracts) {
        return contracts == null ? "同步中" : number(contracts) + " contracts";
    }

    private static String money(BigDecimal value) {
        return value == null ? "同步中" : number(value) + " USDT";
    }

    private static String signedMoney(BigDecimal value) {
        if (value == null) return "同步中";
        return (value.signum() > 0 ? "+" : "") + number(value) + " USDT";
    }

    private static String leverage(BigDecimal value) {
        return value == null ? "同步中" : number(value) + "×";
    }

    private static String percent(BigDecimal value) {
        return value == null ? "同步中" : (value.signum() > 0 ? "+" : "") + number(value) + "%";
    }

    private static String duration(Duration value) {
        if (value == null) return "同步中";
        long seconds = value.toSeconds();
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        if (days > 0) return days + "天" + hours + "小时" + minutes + "分钟";
        if (hours > 0) return hours + "小时" + minutes + "分钟";
        if (minutes > 0) return minutes + "分钟" + seconds % 60 + "秒";
        return seconds + "秒";
    }

    private static String direction(String posSide) {
        if ("long".equalsIgnoreCase(posSide)) return "做多 LONG";
        if ("short".equalsIgnoreCase(posSide)) return "做空 SHORT";
        return "同步中";
    }

    private static String time(Instant value) {
        return value == null ? "同步中" : TIME.format(value);
    }

    private static String line(String label, String value) {
        return escape(String.format(Locale.ROOT, "%-12s%s%n", label, value(value)));
    }

    private static String number(BigDecimal value) {
        if (value == null) return "同步中";
        DecimalFormat format = new DecimalFormat("#,##0.############", DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value.stripTrailingZeros());
    }

    private static String mode(TradingMode mode) {
        return (mode == null ? TradingMode.DISABLED : mode).name();
    }

    private static String title(String type) {
        return hasText(type) ? type : "交易事件";
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "同步中" : value.toString();
    }

    private static String escape(String value) {
        return value(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean same(String left, String right) {
        return hasText(left) && hasText(right) && left.equalsIgnoreCase(right);
    }

    private static boolean isTakeProfitRole(String role) {
        String normalized = value(role).toUpperCase(Locale.ROOT);
        return normalized.contains("TAKE_PROFIT") || normalized.contains("SIDEWAYS_TP")
                || normalized.matches("TP[123]");
    }

    private static BigDecimal positive(BigDecimal preferred, BigDecimal fallback) {
        return preferred != null && preferred.signum() > 0 ? preferred : fallback;
    }

    private static BigDecimal amount(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.multiply(right);
    }

    private record Facts(String owner, PendingOrder order, List<TradeOrderEntity> tradeOrders,
                         List<TradeFillEntity> fills, OkxInstrumentRules instrumentRules) { }
}
