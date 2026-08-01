package io.testhub.platform.account;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountManagementController {
    private final AccountManagementService service;

    public AccountManagementController(AccountManagementService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AccountManagementService.AccountPage> list(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return ApiResponse.of(service.list(principal(authentication), keyword, page, pageSize));
    }

    @GetMapping("/options")
    public ApiResponse<List<UserAccountRepository.AccountOption>> options(Authentication authentication) {
        return ApiResponse.of(service.options(principal(authentication)));
    }

    @PostMapping
    public ApiResponse<UserAccountRepository.ManagedAccount> create(
            Authentication authentication, @Valid @RequestBody AccountRequest request) {
        return ApiResponse.of(service.create(principal(authentication), request.account(), request.displayName(),
                request.email(), request.phone(), request.enabled() == null || request.enabled()));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserAccountRepository.ManagedAccount> update(
            Authentication authentication, @PathVariable String id, @Valid @RequestBody AccountRequest request) {
        return ApiResponse.of(service.update(principal(authentication), id, request.account(), request.displayName(),
                request.email(), request.phone(), request.enabled() == null || request.enabled()));
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> updatePassword(
            Authentication authentication, @PathVariable String id, @Valid @RequestBody PasswordRequest request) {
        service.updatePassword(principal(authentication), id, request.password());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String id) {
        service.delete(principal(authentication), id);
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record AccountRequest(@NotBlank @Size(max = 160) String account,
                                 @Size(max = 80) String displayName,
                                 @Size(max = 190) String email,
                                 @Size(max = 40) String phone,
                                 Boolean enabled) {
    }

    public record PasswordRequest(@NotBlank @Size(min = 5, max = 72) String password) {
    }
}
