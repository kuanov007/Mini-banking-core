package uz.kuanov.minibank.ledger;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import uz.kuanov.minibank.common.security.CurrentUser;

@RestController
@RequestMapping("/api/operations")
@Tag(name = "Operations", description = "Completed money movements")
class OperationController {

    private final LedgerService ledgerService;

    OperationController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an operation (owners of the accounts involved, or ADMIN)")
    OperationResponse get(@PathVariable UUID id, Authentication authentication) {
        return ledgerService.get(id, CurrentUser.from(authentication));
    }

    /**
     * 201 with a Location pointing to {@link #get}: every money movement is created the same way. A replay gets the
     * same status, Location and body as the original, so a client can't tell them apart unless it reads the header.
     */
    static ResponseEntity<OperationResponse> created(OperationResult result) {
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/operations/{id}")
                .buildAndExpand(result.operation().id())
                .toUri();
        ResponseEntity.BodyBuilder response = ResponseEntity.created(location);
        if (result.replayed()) {
            response.header(IdempotencyKey.REPLAYED_HEADER, "true");
        }
        return response.body(result.operation());
    }
}
