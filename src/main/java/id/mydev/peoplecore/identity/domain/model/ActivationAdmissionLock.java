package id.mydev.peoplecore.identity.domain.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "activation_admission_lock")
public class ActivationAdmissionLock {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    protected ActivationAdmissionLock() {
    }

    public static ActivationAdmissionLock singleton() {
        ActivationAdmissionLock lock = new ActivationAdmissionLock();
        lock.id = SINGLETON_ID;
        return lock;
    }

    public Integer getId() {
        return id;
    }
}
