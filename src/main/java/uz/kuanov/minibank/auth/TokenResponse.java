package uz.kuanov.minibank.auth;

/** @param expiresIn token lifetime in seconds, as in OAuth2 token responses */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

    static TokenResponse bearer(String accessToken, long expiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn);
    }
}
