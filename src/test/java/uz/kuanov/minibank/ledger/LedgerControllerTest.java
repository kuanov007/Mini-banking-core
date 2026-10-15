package uz.kuanov.minibank.ledger;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import uz.kuanov.minibank.account.Currency;
import uz.kuanov.minibank.common.error.BusinessException;
import uz.kuanov.minibank.common.error.NotFoundException;
import uz.kuanov.minibank.common.security.CurrentUser;
import uz.kuanov.minibank.common.security.SecurityConfig;

/** Web-layer slice: validation, status codes, role rules and the error format, with the service mocked. */
@WebMvcTest({AdminCashController.class, OperationController.class})
@Import(SecurityConfig.class)
class LedgerControllerTest {

    private static final UUID OPERATION_ID = UUID.fromString("0b9e4bb6-6c3f-4f5e-9d2a-3f1c2e7a8b90");
    private static final String NUMBER = "00000000000000000005";
    private static final OperationResponse DEPOSIT = new OperationResponse(OPERATION_ID, OperationType.DEPOSIT,
            new BigDecimal("1500.00"), Currency.UZS, null, NUMBER, "cash in", Instant.parse("2026-10-15T10:00:00Z"));

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LedgerService ledgerService;

    @Test
    void depositReturns201WithLocationOfTheOperation() throws Exception {
        when(ledgerService.deposit(any(), eq(1L))).thenReturn(DEPOSIT);

        mockMvc.perform(post("/api/admin/deposits").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber": "%s", "amount": 1500.00, "description": "cash in"}
                                """.formatted(NUMBER)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/operations/" + OPERATION_ID))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.fromAccount").isEmpty())
                .andExpect(jsonPath("$.toAccount").value(NUMBER))
                .andExpect(content().string(containsString("\"amount\":1500.00")));
    }

    @Test
    void withdrawalReturns201() throws Exception {
        when(ledgerService.withdraw(any(), eq(1L))).thenReturn(DEPOSIT);

        mockMvc.perform(post("/api/admin/withdrawals").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber": "%s", "amount": 10}
                                """.formatted(NUMBER)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin/deposits", "/api/admin/withdrawals"})
    void customerGets403(String path) throws Exception {
        mockMvc.perform(post(path).with(customer(7))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber": "%s", "amount": 10}
                                """.formatted(NUMBER)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(ledgerService);
    }

    @Test
    void depositWithoutTokenIs401() throws Exception {
        mockMvc.perform(post("/api/admin/deposits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber": "%s", "amount": 10}
                                """.formatted(NUMBER)))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5.00", "10.001", "100000000000000000.00"})
    void rejectsInvalidAmounts(String amount) throws Exception {
        mockMvc.perform(post("/api/admin/deposits").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber": "%s", "amount": %s}
                                """.formatted(NUMBER, amount)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.amount").exists());

        verifyNoInteractions(ledgerService);
    }

    @Test
    void rejectsMissingFieldsMalformedNumberAndLongDescription() throws Exception {
        mockMvc.perform(post("/api/admin/deposits").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.accountNumber").exists())
                .andExpect(jsonPath("$.errors.amount").exists());

        mockMvc.perform(post("/api/admin/deposits").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber": "12345", "amount": 10, "description": "%s"}
                                """.formatted("x".repeat(141))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.accountNumber").value("must be 20 digits"))
                .andExpect(jsonPath("$.errors.description").exists());

        verifyNoInteractions(ledgerService);
    }

    @Test
    void descriptionOf140CharactersIsAccepted() throws Exception {
        when(ledgerService.deposit(any(), anyLong())).thenReturn(DEPOSIT);

        mockMvc.perform(post("/api/admin/deposits").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber": "%s", "amount": 10, "description": "%s"}
                                """.formatted(NUMBER, "x".repeat(140))))
                .andExpect(status().isCreated());
    }

    @Test
    void businessRuleViolationIs422WithCode() throws Exception {
        when(ledgerService.withdraw(any(), anyLong()))
                .thenThrow(new BusinessException("INSUFFICIENT_FUNDS", "Insufficient funds on account " + NUMBER));

        mockMvc.perform(post("/api/admin/withdrawals").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountNumber": "%s", "amount": 10}
                                """.formatted(NUMBER)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void getOperationPassesTheCallerAndMaps404() throws Exception {
        when(ledgerService.get(OPERATION_ID, new CurrentUser(7L, false))).thenReturn(DEPOSIT);
        when(ledgerService.get(OPERATION_ID, new CurrentUser(8L, false)))
                .thenThrow(new NotFoundException("Operation", OPERATION_ID));

        mockMvc.perform(get("/api/operations/{id}", OPERATION_ID).with(customer(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(OPERATION_ID.toString()));
        mockMvc.perform(get("/api/operations/{id}", OPERATION_ID).with(customer(8)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void malformedOperationIdIs400() throws Exception {
        mockMvc.perform(get("/api/operations/{id}", "not-a-uuid").with(customer(7)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private static RequestPostProcessor customer(long userId) {
        return jwt().jwt(token -> token.subject(String.valueOf(userId)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
