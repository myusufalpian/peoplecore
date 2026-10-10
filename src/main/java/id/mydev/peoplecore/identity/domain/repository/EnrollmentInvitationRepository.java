package id.mydev.peoplecore.identity.domain.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import id.mydev.peoplecore.identity.domain.model.EnrollmentInvitation;
public interface EnrollmentInvitationRepository extends JpaRepository<EnrollmentInvitation, Long> {
    Optional<EnrollmentInvitation> findByPublicId(UUID publicId);

    List<EnrollmentInvitation> findByEmployeeIdAndStatus(Long employeeId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invitation from EnrollmentInvitation invitation where invitation.publicId = :publicId")
    Optional<EnrollmentInvitation> lockByPublicId(UUID publicId);
}
