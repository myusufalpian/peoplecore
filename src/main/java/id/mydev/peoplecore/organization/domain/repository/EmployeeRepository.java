package id.mydev.peoplecore.organization.domain.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

import id.mydev.peoplecore.organization.domain.model.Employee;
public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Optional<Employee> findByPublicId(UUID publicId);

    Optional<Employee> findByEmployeeNumber(String employeeNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select employee from Employee employee where employee.id = :id")
    Optional<Employee> lockById(Long id);
}
