package uz.kuanov.minibank.ledger;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import uz.kuanov.minibank.account.Currency;
import uz.kuanov.minibank.common.error.BusinessException;
import uz.kuanov.minibank.common.security.SecurityConfig;

/** Web-layer slice: validation, status codes, role rules and the error format, with the service mocked. */
@WebMvcTest(TransferController.class)
@Import(SecurityConfig.class)
class TransferControllerTest {

    private static final UUID OPERATION_ID = UUID.fromString("5d0c3a1e-2b7f-4c8e-9a61-7e3f0d2b4c15");
    private static final String FROM = "00000000000000000005";
    private static final String TO = "00000000000000000006";
    private static final String KEY = "9b2e4c7a-1f3d-4a8e-b6c0-5d7f9e2a4c18";
    private static final OperationResponse TRANSFER = new OperationResponse(OPERATION_ID, OperationType.TRANSFER,
            new BigDecimal("250.00"), Currency.UZS, FROM, TO, "rent", Instant.parse("2026-10-15T10:00:00Z"));

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LedgerService ledgerService;

    @Test
    void transferReturns201WithLocationOfTheOperation() throws Exception {
        when(ledgerService.transfer(new TransferRequest(FROM, TO, new BigDecimal("250.00"), "rent"), 7L,
                new IdempotencyKey(KEY)))
                .thenReturn(new OperationResult(TRANSFER, false));

        transfer(customer(7), """
                {"fromAccount": "%s", "toAccount": "%s", "amount": 250.00, "description": "rent"}
                """.formatted(FROM, TO))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/operations/" + OPERATION_ID))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.fromAccount").value(FROM))
                .andExpect(jsonPath("$.toAccount").value(TO))
                .andExpect(content().string(containsString("\"amount\":250.00")));
    }

    @Test
    void replayIs201WithTheSameLocationAndTheReplayedHeader() throws Exception {
        when(ledgerService.transfer(any(), eq(7L), eq(new IdempotencyKey(KEY))))
                .thenReturn(new OperationResult(TRANSFER, true));

        transfer(customer(7), validBody())
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/operations/" + OPERATION_ID))
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(OPERATION_ID.toString()));
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mockMvc.perform(post("/api/transfers").with(customer(7))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Required header 'Idempotency-Key' is not present."));

        verifyNoInteractions(ledgerService);
    }

    static Stream<String> invalidIdempotencyKeys() {
        return Stream.of("", "with space", "ключ", "x".repeat(65));
    }

    @ParameterizedTest
    @MethodSource("invalidIdempotencyKeys")
    void invalidIdempotencyKeyIs400(String key) throws Exception {
        transfer(customer(7), key, validBody())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("Idempotency-Key")));

        verifyNoInteractions(ledgerService);
    }

    @Test
    void idempotencyKeyOf64CharactersIsAccepted() throws Exception {
        String key = "k".repeat(64);
        when(ledgerService.transfer(any(), eq(7L), eq(new IdempotencyKey(key))))
                .thenReturn(new OperationResult(TRANSFER, false));

        transfer(customer(7), key, validBody()).andExpect(status().isCreated());
    }

    @Test
    void adminGets403() throws Exception {
        transfer(admin(), validBody())
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(ledgerService);
    }

    @Test
    void withoutTokenIs401() throws Exception {
        mockMvc.perform(post("/api/transfers").header(IdempotencyKey.HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(ledgerService);
    }

    @Test
    void acceptsTheMaximumAmountAndRejectsOneTiyinMore() throws Exception {
        when(ledgerService.transfer(any(), anyLong(), any())).thenReturn(new OperationResult(TRANSFER, false));

        transfer(customer(7), body("1000000000000.00")).andExpect(status().isCreated());
        transfer(customer(7), body("1000000000000.01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").value("must be less than or equal to 1000000000000.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5.00", "10.001", "1000000000000.01", "100000000000000000.00"})
    void rejectsInvalidAmounts(String amount) throws Exception {
        transfer(customer(7), body(amount))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.amount").exists());

        verifyNoInteractions(ledgerService);
    }

    @Test
    void rejectsMissingFieldsMalformedNumbersAndLongDescription() throws Exception {
        transfer(customer(7), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fromAccount").exists())
                .andExpect(jsonPath("$.errors.toAccount").exists())
                .andExpect(jsonPath("$.errors.amount").exists());

        transfer(customer(7), """
                {"fromAccount": "12345", "toAccount": "0000000000000000000x", "amount": 10, "description": "%s"}
                """.formatted("x".repeat(141)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fromAccount").value("must be 20 digits"))
                .andExpect(jsonPath("$.errors.toAccount").value("must be 20 digits"))
                .andExpect(jsonPath("$.errors.description").exists());

        verifyNoInteractions(ledgerService);
    }

    @Test
    void descriptionOf140CharactersIsAccepted() throws Exception {
        when(ledgerService.transfer(any(), anyLong(), any())).thenReturn(new OperationResult(TRANSFER, false));

        transfer(customer(7), """
                {"fromAccount": "%s", "toAccount": "%s", "amount": 10, "description": "%s"}
                """.formatted(FROM, TO, "x".repeat(140)))
                .andExpect(status().isCreated());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        transfer(customer(7), "{\"fromAccount\": ")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(ledgerService);
    }

    @Test
    void businessRuleViolationIs422WithCode() throws Exception {
        when(ledgerService.transfer(any(), eq(7L), any()))
                .thenThrow(new BusinessException("INSUFFICIENT_FUNDS", "Insufficient funds on account " + FROM));

        transfer(customer(7), validBody())
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.instance").value("/api/transfers"));
    }

    private ResultActions transfer(RequestPostProcessor user, String json) throws Exception {
        return transfer(user, KEY, json);
    }

    private ResultActions transfer(RequestPostProcessor user, String idempotencyKey, String json) throws Exception {
        return mockMvc.perform(post("/api/transfers").with(user)
                .header(IdempotencyKey.HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static String validBody() {
        return body("10");
    }

    private static String body(String amount) {
        return """
                {"fromAccount": "%s", "toAccount": "%s", "amount": %s}
                """.formatted(FROM, TO, amount);
    }

    private static RequestPostProcessor customer(long userId) {
        return jwt().jwt(token -> token.subject(String.valueOf(userId)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
