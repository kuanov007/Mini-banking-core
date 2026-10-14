package uz.kuanov.minibank;

import org.springframework.boot.SpringApplication;

/**
 * Runs the app locally against a throwaway PostgreSQL container: no Compose or local database needed.
 * Start it from the IDE, or with {@code ./mvnw spring-boot:test-run}.
 */
public class TestApplication {

    public static void main(String[] args) {
        SpringApplication.from(Application::main)
                .with(TestcontainersConfig.class)
                .run(args);
    }
}
