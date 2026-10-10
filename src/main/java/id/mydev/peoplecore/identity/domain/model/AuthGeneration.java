package id.mydev.peoplecore.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "auth_generation")
public class AuthGeneration {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    @Column(name = "generation", nullable = false)
    private long generation;

    protected AuthGeneration() {
    }

    public AuthGeneration(Integer id, long generation) {
        this.id = id;
        this.generation = generation;
    }

    public long next() {
        generation++;
        return generation;
    }

    public Integer getId() {
        return id;
    }

    public long getGeneration() {
        return generation;
    }
}
