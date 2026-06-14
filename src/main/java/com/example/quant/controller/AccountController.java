package com.example.quant.controller;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.OkxAccountBindingService;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.OkxAccountBindRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/account")
public class AccountController {
    private final AccountSnapshotService accountSnapshotService;
    private final PositionSnapshotService positionSnapshotService;
    private final OkxAccountBindingService okxAccountBindingService;

    public AccountController(AccountSnapshotService accountSnapshotService, PositionSnapshotService positionSnapshotService,
                             OkxAccountBindingService okxAccountBindingService) {
        this.accountSnapshotService = accountSnapshotService;
        this.positionSnapshotService = positionSnapshotService;
        this.okxAccountBindingService = okxAccountBindingService;
    }

    @GetMapping("/summary")
    public Object summary() {
        return accountSnapshotService.summary();
    }

    @GetMapping("/positions")
    public Object positions() {
        return positionSnapshotService.positions();
    }

    @GetMapping("/binding-status")
    public Object bindingStatus() {
        return okxAccountBindingService.status();
    }

    @PostMapping("/verify")
    public Object verify() {
        return accountSnapshotService.verifyOkx();
    }

    @PostMapping("/bind")
    public Object bind(@RequestBody OkxAccountBindRequest request) {
        return okxAccountBindingService.bind(request);
    }

    @PostMapping("/unbind")
    public Object unbind() {
        return okxAccountBindingService.unbind();
    }
}
