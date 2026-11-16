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
        return repository.currentValue().orElse(0L);
    }

    @Transactional
    public long increment() {
        if (repository.advance() == 0) {
            repository.saveAndFlush(new AuthGeneration(AuthGeneration.SINGLETON_ID, 1L));
        }
        return repository.currentValue().orElseThrow(() -> new IllegalStateException("Authorization generation unavailable"));
    }
}
