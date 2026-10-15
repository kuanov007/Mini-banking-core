package uz.kuanov.minibank.ledger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import uz.kuanov.minibank.common.security.CurrentUser;

/** Cash at the counter. Everything under /api/admin/** is ADMIN-only (SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin: cash", description = "Cash deposits and withdrawals by a bank operator")
class AdminCashController {

    private final LedgerService ledgerService;

    AdminCashController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping("/deposits")
    @Operation(summary = "Deposit cash to a customer account (ADMIN)")
    ResponseEntity<OperationResponse> deposit(@Valid @RequestBody CashOperationRequest request,
                                              Authentication authentication) {
        return OperationController.created(ledgerService.deposit(request, CurrentUser.from(authentication).id()));
    }

    @PostMapping("/withdrawals")
    @Operation(summary = "Withdraw cash from a customer account (ADMIN)")
    ResponseEntity<OperationResponse> withdraw(@Valid @RequestBody CashOperationRequest request,
                                               Authentication authentication) {
        return OperationController.created(ledgerService.withdraw(request, CurrentUser.from(authentication).id()));
    }
}
