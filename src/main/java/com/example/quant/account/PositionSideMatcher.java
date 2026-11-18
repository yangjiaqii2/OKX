package com.example.quant.account;

import com.example.quant.account.dto.PositionSummary;
import java.math.BigDecimal;

/** Matches exchange positions without discarding the sign of a net position. */
public final class PositionSideMatcher {
    private PositionSideMatcher() {}

    public static boolean matches(String instId, String posSide, String entrySide, PositionSummary position) {
        if (position == null || instId == null || !instId.equalsIgnoreCase(position.instId())) {
            return false;
        }
        BigDecimal size;
        try {
            size = new BigDecimal(position.size());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("POSITION_SIZE_UNAVAILABLE: " + instId, ex);
        }
        if (size.signum() == 0) return false;
        String actual;
        if ("net".equalsIgnoreCase(position.posSide())) {
            actual = size.signum() > 0 ? "long" : "short";
        } else if ("long".equalsIgnoreCase(position.posSide()) || "short".equalsIgnoreCase(position.posSide())) {
            actual = position.posSide();
        } else {
            throw new IllegalStateException("POSITION_DIRECTION_UNAVAILABLE: " + instId);
        }
        String expected = entryDirection(posSide, entrySide);
        return expected == null || expected.equalsIgnoreCase(actual);
    }

    public static String entryDirection(String posSide, String entrySide) {
        if ("long".equalsIgnoreCase(posSide)) return "long";
        if ("short".equalsIgnoreCase(posSide)) return "short";
        if ("buy".equalsIgnoreCase(entrySide)) return "long";
        if ("sell".equalsIgnoreCase(entrySide)) return "short";
        if (posSide == null || posSide.isBlank() || "net".equalsIgnoreCase(posSide)) return null;
        throw new IllegalStateException("LOCAL_POSITION_DIRECTION_UNAVAILABLE");
    }
}
