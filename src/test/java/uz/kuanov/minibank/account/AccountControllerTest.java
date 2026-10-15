package uz.kuanov.minibank.account;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import uz.kuanov.minibank.common.error.NotFoundException;
import uz.kuanov.minibank.common.security.CurrentUser;
import uz.kuanov.minibank.common.security.SecurityConfig;

/** Web-layer slice: validation, status codes, role rules and the error format, with the service mocked. */
@WebMvcTest({AccountController.class, AdminAccountController.class})
@Import(SecurityConfig.class)
class AccountControllerTest {

    private static final AccountResponse ACCOUNT = new AccountResponse(5L, "00000000000000000005", Currency.UZS,
            new BigDecimal("0.00"), AccountStatus.ACTIVE, Instant.parse("2026-10-15T10:00:00Z"));

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AccountService accountService;

    @Test
    void openReturns201WithLocationAndBalanceWithTwoDecimals() throws Exception {
        when(accountService.open(7L, Currency.UZS)).thenReturn(ACCOUNT);

        mockMvc.perform(post("/api/accounts").with(customer(7))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency": "UZS"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/accounts/5"))
                .andExpect(jsonPath("$.number").value("00000000000000000005"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(content().string(containsString("\"balance\":0.00")));
    }

    @Test
    void openRequiresCurrency() throws Exception {
        mockMvc.perform(post("/api/accounts").with(customer(7))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.currency").exists());

        verifyNoInteractions(accountService);
    }

    @Test
    void openRejectsUnsupportedCurrency() throws Exception {
        mockMvc.perform(post("/api/accounts").with(customer(7))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency": "EUR"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(accountService);
    }

    @Test
    void openWithoutTokenIs401() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency": "UZS"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void adminCannotOpenOrListCustomerAccounts() throws Exception {
        mockMvc.perform(post("/api/accounts").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency": "UZS"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get("/api/accounts").with(admin()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(accountService);
    }

    @Test
    void listReturnsOnlyTheCallersAccounts() throws Exception {
        when(accountService.listOwn(7L)).thenReturn(List.of(ACCOUNT));

        mockMvc.perform(get("/api/accounts").with(customer(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(5));
    }

    @Test
    void getPassesTheCallerToTheServiceAndMapsNotFoundTo404() throws Exception {
        when(accountService.get(eq(5L), any(CurrentUser.class))).thenThrow(new NotFoundException("Account", 5L));

        mockMvc.perform(get("/api/accounts/{id}", 5).with(customer(8)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Account with id 5 not found"));
    }

    @Test
    void adminGetIsPassedAsAdmin() throws Exception {
        when(accountService.get(5L, new CurrentUser(1L, true))).thenReturn(ACCOUNT);

        mockMvc.perform(get("/api/accounts/{id}", 5).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    void customerCannotChangeStatus() throws Exception {
        mockMvc.perform(patch("/api/admin/accounts/{id}/status", 5).with(customer(7))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "FROZEN"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(accountService);
    }

    @Test
    void changeStatusRequiresStatus() throws Exception {
        mockMvc.perform(patch("/api/admin/accounts/{id}/status", 5).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.status").exists());

        verifyNoInteractions(accountService);
    }

    @Test
    void adminFreezesAccount() throws Exception {
        when(accountService.changeStatus(5L, AccountStatus.FROZEN)).thenReturn(new AccountResponse(5L,
                ACCOUNT.number(), ACCOUNT.currency(), ACCOUNT.balance(), AccountStatus.FROZEN, ACCOUNT.createdAt()));

        mockMvc.perform(patch("/api/admin/accounts/{id}/status", 5).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "FROZEN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    private static RequestPostProcessor customer(long userId) {
        return jwt().jwt(token -> token.subject(String.valueOf(userId)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
