package id.mydev.peoplecore.organization.domain.repository;

import id.mydev.peoplecore.organization.domain.model.ProfileRevisionChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProfileRevisionChangeRepository extends JpaRepository<ProfileRevisionChange, Long> {
    List<ProfileRevisionChange> findByRevisionId(Long revisionId);

    List<ProfileRevisionChange> findByRevisionIdIn(List<Long> revisionIds);
}
