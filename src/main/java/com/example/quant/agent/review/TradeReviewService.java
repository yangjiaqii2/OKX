package com.example.quant.agent.review;

import com.example.quant.account.ClosePositionRecordEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeReviewService {
    private final TradeReviewRepository repository;
    private final Clock clock;

    @Autowired
    public TradeReviewService(TradeReviewRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public TradeReviewService(TradeReviewRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Transactional
    public TradeReviewView reviewClosedTrade(ClosePositionRecordEntity record) {
        if (record == null || record.getId() == null) {
            throw new IllegalArgumentException("平仓记录ID不能为空");
        }
        return repository.findFirstByClosePositionRecordId(record.getId())
                .map(TradeReviewView::from)
                .orElseGet(() -> createReview(record));
    }

    private TradeReviewView createReview(ClosePositionRecordEntity record) {
        ReviewDecision decision = classify(record);
        Instant now = Instant.now(clock);
        TradeReviewEntity entity = new TradeReviewEntity();
        entity.setUserName(record.getUserName());
        entity.setInstId(record.getInstId());
        entity.setPendingOrderId(record.getPendingOrderId());
        entity.setAutoTradeRecordId(record.getAutoTradeRecordId());
        entity.setClosePositionRecordId(record.getId());
        entity.setReviewReason(decision.reviewReason());
        entity.setStrategyTag(decision.strategyTag());
        entity.setImprovementHint(decision.improvementHint());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return TradeReviewView.from(repository.save(entity));
    }

    private static ReviewDecision classify(ClosePositionRecordEntity record) {
        String source = upper(record.getSource());
        String error = upper(record.getErrorMessage());
        BigDecimal pnl = record.getRealizedPnl() == null ? BigDecimal.ZERO : record.getRealizedPnl();
        if (source.contains("MAX_HOLD")) {
            return new ReviewDecision("最大持仓时间退出", "MAX_HOLD_TIME_EXIT",
                    "统计该策略持仓时间分布，若频繁触发最大持仓退出，应下调候选评分或缩短等待确认。");
        }
        if (source.contains("SIDEWAYS")) {
            return new ReviewDecision("横盘小盈利退出", "SIDEWAYS_SMALL_PROFIT_EXIT",
                    "复盘入场后波动收敛原因，优化横盘识别和小盈利退出阈值。");
        }
        if (error.contains("PROTECTION")) {
            return new ReviewDecision("保护单失败", "PROTECTION_FAILED",
                    "检查保护单提交、撤单和持仓模式，失败样本应降低后续同类自动执行权重。");
        }
        if (error.contains("SLIPPAGE") || error.contains("LIQUIDITY")) {
            return new ReviewDecision("流动性/滑点问题", "LIQUIDITY_SLIPPAGE",
                    "提高深度和滑点门槛，必要时改用分批限价入场。");
        }
        if (error.contains("NEWS")) {
            return new ReviewDecision("新闻冲击", "NEWS_SHOCK",
                    "将相关关键词加入新闻风险拦截，并扩大事件窗口。");
        }
        if (error.contains("TREND_REVERSAL")) {
            return new ReviewDecision("趋势反转失败", "TREND_REVERSAL_FAILED",
                    "复核大周期结构和 BTC/ETH 联动，降低逆势或弱结构候选评分。");
        }
        if (error.contains("TIGHT_STOP")) {
            return new ReviewDecision("止损过窄", "STOP_LOSS_TOO_TIGHT",
                    "检查ATR和结构低点/高点距离，避免止损落在常规噪声区。");
        }
        if (source.contains("TP") || pnl.signum() > 0) {
            return new ReviewDecision("正常止盈", "NORMAL_TAKE_PROFIT",
                    "记录有效因子，后续候选评分可提高相似结构权重。");
        }
        if (source.contains("SL") || source.contains("STOP")) {
            return new ReviewDecision("正常止损", "NORMAL_STOP_LOSS",
                    "检查风险收益比和止损位置，保留样本用于评估策略期望值。");
        }
        if (pnl.signum() < 0) {
            return new ReviewDecision("入场过早", "ENTRY_TOO_EARLY",
                    "检查5m确认、结构破位和回踩条件，避免仅凭方向字段提前入场。");
        }
        return new ReviewDecision("横盘小盈利退出", "SIDEWAYS_SMALL_PROFIT_EXIT",
                "收益接近零时优先复核是否存在横盘退出或保护单替换机会。");
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private record ReviewDecision(String reviewReason, String strategyTag, String improvementHint) {
    }
}
