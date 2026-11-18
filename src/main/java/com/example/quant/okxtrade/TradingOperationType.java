package com.example.quant.okxtrade;

public enum TradingOperationType {
    OPEN_POSITION(false),
    ADD_POSITION(false),
    CLOSE_POSITION(true),
    REDUCE_POSITION(true),
    PLACE_STOP_LOSS(true),
    PLACE_TAKE_PROFIT(true),
    CANCEL_ORDER(false),
    CANCEL_PROTECTION(false),
    /** In-place protective-stop change (POST /api/v5/trade/amend-algos). */
    AMEND_PROTECTION(true),
    SET_LEVERAGE(false);

    private final boolean reducesExposure;

    TradingOperationType(boolean reducesExposure) {
        this.reducesExposure = reducesExposure;
    }

    public boolean reducesExposure() {
        return reducesExposure;
    }
}
