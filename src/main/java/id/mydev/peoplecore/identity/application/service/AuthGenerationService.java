package id.mydev.peoplecore.identity.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import id.mydev.peoplecore.identity.domain.model.AuthGeneration;
import id.mydev.peoplecore.identity.domain.repository.AuthGenerationRepository;
@Service
public class AuthGenerationService {
    private final AuthGenerationRepository repository;

    public AuthGenerationService(AuthGenerationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public long current() {
        return repository.findById(AuthGeneration.SINGLETON_ID)
            .map(AuthGeneration::getGeneration)
            .orElse(0L);
    }

    @Transactional
    public long increment() {
        AuthGeneration generation = repository.lockSingleton()
            .orElseGet(() -> new AuthGeneration(AuthGeneration.SINGLETON_ID, 0L));
        long next = generation.next();
        repository.save(generation);
        return next;
    }
}
