package uz.kuanov.minibank.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bootstrap admin, env {@code APP_ADMIN_USERNAME} / {@code APP_ADMIN_PASSWORD}. Both unset means no admin is created.
 */
@ConfigurationProperties("app.admin")
public record AdminProperties(String username, String password) {
}
