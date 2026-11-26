package com.example.quant.crypto.filter;

import com.example.quant.agent.market.OrderBookLiquidityService;
import com.example.quant.agent.market.OrderBookLiquiditySnapshot;
import com.example.quant.agent.scan.ScanContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Screens the survivors of the ticker filters before allocating expensive analysis slots. */
public final class OrderBookPrefilter {
    private static final int MAX_CONCURRENT = 4;
    // Reserve order-book capacity for deep analysis and order confirmation.
    private static final int REQUESTS_PER_WINDOW = 24;
    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(2);
    private final ArrayDeque<Long> requestStarts = new ArrayDeque<>();
    private final OrderBookLiquidityService books;

    public OrderBookPrefilter(OrderBookLiquidityService books) {
        this.books = books;
    }

    public FilterOutcome apply(FilterOutcome input, ScanContext ctx) {
        var passed = new ArrayList<FilterContext>();
        var rejected = new ArrayList<>(input.rejected());
        var candidates = input.passed();
        int unavailable = 0;
        input.funnel().recordStage("before_order_book", candidates.size());
        if (!candidates.isEmpty()) {
            var pool = Executors.newFixedThreadPool(Math.min(MAX_CONCURRENT, candidates.size()));
            try {
                List<Callable<OrderBookLiquiditySnapshot>> tasks = candidates.stream()
                        .<Callable<OrderBookLiquiditySnapshot>>map(candidate -> () -> {
                            awaitRequestSlot();
                            if (ctx.isExpired()) return null;
                            return books.snapshot(candidate.instId(), ctx.requestTimeout());
                        }).toList();
                var results = pool.invokeAll(tasks, ctx.remainingTime().toMillis(), TimeUnit.MILLISECONDS);
                int incomplete = 0;
                for (int i = 0; i < candidates.size(); i++) {
                    var candidate = candidates.get(i);
                    OrderBookLiquiditySnapshot book = null;
                    try {
                        book = results.get(i).get();
                    } catch (CancellationException ex) {
                        incomplete++;
                    } catch (ExecutionException ex) {
                        // Isolate a failed quote; its contract cannot qualify in this round.
                    }
                    if (book != null && book.tradable()) {
                        passed.add(candidate);
                    } else {
                        String reason = book == null ? "BOOK_UNAVAILABLE" : book.denyReason();
                        if (book == null || book.midPrice().signum() == 0) unavailable++;
                        rejected.add(new FilterOutcome.RejectedCandidate(candidate,
                                FilterResult.reject("order_book", reason)));
                    }
                }
                if (incomplete > 0 || ctx.isExpired()) {
                    ctx.addSkippedByDeadline(incomplete);
                    ctx.markPartialTimeout();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                ctx.addSkippedByDeadline(candidates.size());
                ctx.markPartialTimeout();
            } finally {
                pool.shutdownNow();
            }
        }
        input.funnel().recordStage("after_order_book", passed.size());
        input.funnel().recordStage("order_book_unavailable", unavailable);
        return new FilterOutcome(passed, rejected, input.funnel());
    }

    private synchronized void awaitRequestSlot() throws InterruptedException {
        while (true) {
            long now = System.nanoTime();
            while (!requestStarts.isEmpty() && now - requestStarts.getFirst() >= WINDOW_NANOS) {
                requestStarts.removeFirst();
            }
            if (requestStarts.size() < REQUESTS_PER_WINDOW) {
                requestStarts.addLast(now);
                return;
            }
            TimeUnit.NANOSECONDS.timedWait(this, WINDOW_NANOS - (now - requestStarts.getFirst()));
        }
    }
}
