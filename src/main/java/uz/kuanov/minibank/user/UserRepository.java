package uz.kuanov.minibank.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Callers pass usernames through {@link User#normalizeUsername(String)} first. */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}
