package id.mydev.peoplecore.identity.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

import id.mydev.peoplecore.identity.domain.model.RoleAssignment;
public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, Long> {
    List<RoleAssignment> findByAccountId(Long accountId);
}
