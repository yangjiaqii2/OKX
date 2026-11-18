package com.example.quant.crypto.filter;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Universe 硬资格编排器。
 *
 * <p>职责：<ul>
 *   <li>按顺序跑 {@link UniverseFilter} 链（cheapest first → most expensive last）</li>
 *   <li>第一个 REJECT 即终止后续 Filter（短路）</li>
 *   <li>维护 {@link FilterFunnel} 统计</li>
 *   <li>任何 Filter 抛异常必须隔离，整体不崩溃（UniverseFilter 实现保证不抛；
 *       保险起见本类再 catch 一次）</li>
 * </ul>
 *
 * <p>Filter 顺序：{@code MarketCap → Circulation → VolumeMcap → PriceRange → Liquidity}。
 * 理由：MarketCap 是单一查询通常最贵（外部 API）；PriceRange / Liquidity 是 OKX ticker
 * 派生，零成本；VolumeMcap 依赖 MarketCap。
 */
@Component
public class UniverseHardFilter {
    private static final Logger log = LoggerFactory.getLogger(UniverseHardFilter.class);

    private final List<UniverseFilter> filters;

    public UniverseHardFilter(List<UniverseFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            throw new IllegalArgumentException("filters must not be empty");
        }
        this.filters = List.copyOf(filters);
    }

    public List<UniverseFilter> filters() {
        return filters;
    }

    /**
     * Noop 工厂：所有 Filter 永远 PASS。用于 7 参构造器向后兼容。
     */
    public static UniverseHardFilter noop() {
        return new UniverseHardFilter(List.of(new UniverseFilter() {
            @Override
            public String name() {
                return "noop";
            }

            @Override
            public FilterResult apply(FilterContext context) {
                return FilterResult.pass("noop");
            }
        }));
    }

    /** 对单个输入跑全部 Filter。短路：第一个 REJECT 立即返回。 */
    public SingleOutcome apply(FilterContext input) {
        for (UniverseFilter filter : filters) {
            FilterResult r;
            try {
                r = filter.apply(input);
            } catch (RuntimeException ex) {
                log.warn("UniverseFilter {} threw exception instId={} message={}",
                        filter.name(), input.instId(), ex.getMessage());
                r = FilterResult.reject(filter.name(), "FILTER_EXCEPTION:" + ex.getMessage());
            }
            if (!r.passed()) {
                return new SingleOutcome(false, r);
            }
        }
        return new SingleOutcome(true, FilterResult.pass("all"));
    }

    /** 批量跑：维护漏斗统计，短路每个 candidate。 */
    public FilterOutcome applyBatch(List<FilterContext> inputs) {
        FilterFunnel funnel = new FilterFunnel();
        funnel.recordStage("universe_inputs", inputs.size());
        List<FilterContext> current = new ArrayList<>(inputs);
        List<FilterOutcome.RejectedCandidate> rejected = new ArrayList<>();
        for (UniverseFilter filter : filters) {
            List<FilterContext> next = new ArrayList<>(current.size());
            for (FilterContext ctx : current) {
                FilterResult r;
                try {
                    r = filter.apply(ctx);
                } catch (RuntimeException ex) {
                    log.warn("UniverseFilter {} threw exception instId={} message={}",
                            filter.name(), ctx.instId(), ex.getMessage());
                    r = FilterResult.reject(filter.name(), "FILTER_EXCEPTION:" + ex.getMessage());
                }
                if (r.passed()) {
                    next.add(ctx);
                } else {
                    rejected.add(new FilterOutcome.RejectedCandidate(ctx, r));
                }
            }
            funnel.recordStage("after_" + filter.name(), next.size());
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }
        funnel.recordAccepted();
        funnel.recordRejected();
        return new FilterOutcome(current, rejected, funnel);
    }

    public record SingleOutcome(boolean passed, FilterResult result) {
    }
}
