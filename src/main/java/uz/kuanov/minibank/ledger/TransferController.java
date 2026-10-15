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

/** POST /api/transfers is CUSTOMER-only (SecurityConfig): an admin moves cash, not customers' money. */
@RestController
@RequestMapping("/api/transfers")
@Tag(name = "Transfers", description = "Money between customer accounts")
class TransferController {

    private final LedgerService ledgerService;

    TransferController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping
    @Operation(summary = "Transfer from one of your accounts to any customer account (CUSTOMER)")
    ResponseEntity<OperationResponse> transfer(@Valid @RequestBody TransferRequest request,
                                               Authentication authentication) {
        return OperationController.created(ledgerService.transfer(request, CurrentUser.from(authentication).id()));
    }
}
