package com.example.quant.controller;

import com.example.quant.ai.SaveUserLlmProfileRequest;
import com.example.quant.ai.UserLlmProfileService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/llm/profiles")
public class UserLlmProfileController {
    private final UserLlmProfileService profileService;

    public UserLlmProfileController(UserLlmProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public Object list() {
        return profileService.listForCurrentUser();
    }

    @GetMapping("/status")
    public Object status() {
        return profileService.statusForCurrentUser();
    }

    @PostMapping
    public Object save(@RequestBody SaveUserLlmProfileRequest request) {
        return profileService.saveForCurrentUser(request);
    }

    @PostMapping("/{id}/activate")
    public Object activate(@PathVariable Long id) {
        return profileService.activateForCurrentUser(id);
    }

    @PostMapping("/{id}/test")
    public Object test(@PathVariable Long id) {
        return profileService.testConnectionForCurrentUser(id);
    }

    @DeleteMapping("/{id}")
    public Object delete(@PathVariable Long id) {
        profileService.deleteForCurrentUser(id);
        return profileService.statusForCurrentUser();
    }
}
