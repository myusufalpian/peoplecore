package id.mydev.peoplecore.common.command;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CommandReceiptRepository extends JpaRepository<CommandReceipt, Long> {

    Optional<CommandReceipt> findByActorIdAndCommandTypeAndIdempotencyKey(
        String actorId,
        String commandType,
        String idempotencyKey
    );
}
