package com.example.quant.agent.execution;

import java.util.List;

public record PreConfirmRefreshResult(boolean passed, List<String> reasons) {
    public PreConfirmRefreshResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static PreConfirmRefreshResult passed(String reason) {
        return new PreConfirmRefreshResult(true, List.of(reason));
    }

    public static PreConfirmRefreshResult rejected(List<String> reasons) {
        return new PreConfirmRefreshResult(false, reasons);
    }
}
