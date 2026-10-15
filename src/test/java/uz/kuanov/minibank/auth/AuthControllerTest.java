package uz.kuanov.minibank.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import uz.kuanov.minibank.common.error.BusinessException;
import uz.kuanov.minibank.common.security.SecurityConfig;
import uz.kuanov.minibank.user.Role;

/** Web-layer slice: validation, status codes and the error format, with the real security rules applied. */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AuthService authService;

    @Test
    void registerIsPublicAndReturns201() throws Exception {
        when(authService.register(any())).thenReturn(
                new UserResponse(1L, "azamat", "Azamat K", Role.CUSTOMER, Instant.parse("2026-10-14T10:00:00Z")));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "azamat", "password": "s3cret-password", "fullName": "Azamat K"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void registerRejectsInvalidFieldsWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "a b", "password": "short", "fullName": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.errors.fullName").exists());

        verifyNoInteractions(authService);
    }

    @Test
    void registerRejectsShortPasswordWithReadableMessage() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "azamat", "password": "1234567", "fullName": "Azamat K"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.password").value("must be at least 8 characters"));

        verifyNoInteractions(authService);
    }

    @Test
    void registerRejectsPasswordOver72Utf8BytesWith400Not500() throws Exception {
        // 40 Cyrillic letters: within 72 characters, but 80 bytes, more than BCrypt accepts
        String password = "ж".repeat(40);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "azamat", "password": "%s", "fullName": "Azamat K"}
                                """.formatted(password)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.password").value("must be at most 72 bytes in UTF-8"));

        verifyNoInteractions(authService);
    }

    @Test
    void registerWithTakenUsernameReturns422WithCode() throws Exception {
        when(authService.register(any())).thenThrow(new BusinessException("USERNAME_TAKEN", "This username is already taken"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "azamat", "password": "s3cret-password", "fullName": "Azamat K"}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    void loginIsPublicAndReturnsBearerToken() throws Exception {
        when(authService.login(any())).thenReturn(TokenResponse.bearer("header.payload.signature", 1800));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "azamat", "password": "s3cret-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("header.payload.signature"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800));
    }

    @Test
    void loginWithBadCredentialsReturns401ProblemDetail() throws Exception {
        when(authService.login(any())).thenThrow(new BadCredentialsException("Invalid username or password"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "azamat", "password": "wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password."));
    }
}
