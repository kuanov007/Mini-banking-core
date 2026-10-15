package uz.kuanov.minibank.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Everything under /api/admin/** is ADMIN-only (SecurityConfig). */
@RestController
@RequestMapping("/api/admin/accounts")
@Tag(name = "Admin: accounts", description = "Bank operator actions on accounts")
class AdminAccountController {

    private final AccountService accountService;

    AdminAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Freeze or unfreeze an account (ADMIN)")
    AccountResponse changeStatus(@PathVariable long id, @Valid @RequestBody ChangeAccountStatusRequest request) {
        return accountService.changeStatus(id, request.status());
    }
}
