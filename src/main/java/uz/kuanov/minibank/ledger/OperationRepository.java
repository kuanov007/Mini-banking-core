package uz.kuanov.minibank.ledger;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OperationRepository extends JpaRepository<Operation, UUID> {

    /** One query for the operation, both entries and their accounts: the response needs all of them. */
    @Query("select o from Operation o join fetch o.entries e join fetch e.account where o.id = :id")
    Optional<Operation> findWithEntriesById(UUID id);

    /** The operation an earlier request with this key created, if any; served by the unique constraint's index. */
    @Query("select o from Operation o join fetch o.entries e join fetch e.account"
            + " where o.initiatedBy = :initiatorId and o.idempotencyKey = :key")
    Optional<Operation> findWithEntriesByInitiatorAndKey(long initiatorId, String key);
}
