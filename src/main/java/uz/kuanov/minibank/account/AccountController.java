package uz.kuanov.minibank.account;

import java.net.URI;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import uz.kuanov.minibank.common.security.CurrentUser;

/** Role rules (CUSTOMER for open and list) live in SecurityConfig; ownership is checked in the service. */
@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Accounts", description = "Customer accounts")
class AccountController {

    private final AccountService accountService;

    AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Open an account in the given currency (CUSTOMER)")
    ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request, Authentication authentication) {
        AccountResponse opened = accountService.open(CurrentUser.from(authentication).id(), request.currency());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(opened.id())
                .toUri();
        return ResponseEntity.created(location).body(opened);
    }

    @GetMapping
    @Operation(summary = "List my accounts (CUSTOMER)")
    List<AccountResponse> listMine(Authentication authentication) {
        return accountService.listOwn(CurrentUser.from(authentication).id());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an account (owner or ADMIN)")
    AccountResponse get(@PathVariable long id, Authentication authentication) {
        return accountService.get(id, CurrentUser.from(authentication));
    }
}
