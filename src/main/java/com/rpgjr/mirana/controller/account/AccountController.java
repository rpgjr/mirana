package com.rpgjr.mirana.controller.account;

import com.rpgjr.mirana.service.account.AccountService;

import com.rpgjr.mirana.model.dto.account.AccountDtos.*;
import com.rpgjr.mirana.model.dto.auth.AuthDtos.StatusResponse;
import com.rpgjr.mirana.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountService account;

    public AccountController(AccountService account) {
        this.account = account;
    }

    @GetMapping
    AccountResponse get(@AuthenticationPrincipal AuthUser user) {
        return account.get(user.id());
    }

    @PatchMapping
    AccountResponse rename(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody UpdateProfileRequest r) {
        return account.rename(user.id(), r.displayName());
    }

    @PostMapping("/password")
    StatusResponse password(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ChangePasswordRequest r,
            HttpServletRequest req) {
        account.changePassword(user.id(), r.currentPassword(), r.newPassword(), req);
        return new StatusResponse("PASSWORD_CHANGED", "Password changed. Your other sessions were signed out.");
    }

    @GetMapping("/devices")
    List<DeviceResponse> devices(@AuthenticationPrincipal AuthUser user, HttpServletRequest req) {
        return account.devices(user.id(), req);
    }

    @DeleteMapping("/devices/{id}")
    ResponseEntity<Void> removeDevice(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        account.removeDevice(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/sign-out-others")
    ResponseEntity<Void> signOutOthers(@AuthenticationPrincipal AuthUser user, HttpServletRequest req) {
        account.signOutOthers(user.id(), req);
        return ResponseEntity.noContent().build();
    }
}
