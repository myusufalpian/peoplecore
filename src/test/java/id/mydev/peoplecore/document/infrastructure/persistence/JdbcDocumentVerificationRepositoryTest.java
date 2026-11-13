package id.mydev.peoplecore.document.infrastructure.persistence;

import id.mydev.peoplecore.document.domain.exception.DocumentVerificationException;
import id.mydev.peoplecore.document.domain.repository.DocumentVerificationRepository;
import id.mydev.peoplecore.document.domain.repository.DocumentVerificationRepository.Attempt;
import id.mydev.peoplecore.document.domain.repository.DocumentVerificationRepository.VerifiedContent;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcDocumentVerificationRepositoryTest {
    private static final Attempt ATTEMPT = new Attempt(1, UUID.randomUUID(), 3, 4, UUID.randomUUID(),
        "application/pdf", 12);
    private static final UUID TOKEN = UUID.randomUUID();

    @Test
    void staleGenerationRemainsADomainFailureThroughJpaExceptionTranslation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertFailure("Document verification is no longer current", () ->
            repository(jdbc).finalizeVersion(ATTEMPT, TOKEN, content("scanner")));
    }

    @Test
    void expiredLeaseRemainsADomainFailureThroughJpaExceptionTranslation() throws Exception {
        JdbcTemplate jdbc = databaseWithCurrentParent();
        assertFailure("Document verification lease expired", () ->
            repository(jdbc).finalizeVersion(ATTEMPT, TOKEN, content("scanner")));
    }

    @Test
    void invalidScannerIdentityRemainsADomainFailureThroughJpaExceptionTranslation() throws Exception {
        JdbcTemplate jdbc = databaseWithCurrentParent();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertFailure("Scanner identity is too long", () ->
            repository(jdbc).finalizeVersion(ATTEMPT, TOKEN, content("s".repeat(256))));
    }

    @Test
    void staleFinalUpdateRemainsADomainFailureThroughJpaExceptionTranslation() throws Exception {
        JdbcTemplate jdbc = databaseWithCurrentParent();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(8L);
        assertFailure("Document verification is no longer current", () ->
            repository(jdbc).finalizeVersion(ATTEMPT, TOKEN, content("scanner")));
    }

    @Test
    void recoveryInsertFailureRemainsADomainFailureThroughJpaExceptionTranslation() throws Exception {
        JdbcTemplate jdbc = databaseWithCurrentParent();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 1, 0);
        assertFailure("Document verification recovery failed", () -> repository(jdbc).recover(1000));
    }

    private static JdbcTemplate databaseWithCurrentParent() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong(1)).thenReturn(1L);
        when(row.getLong(2)).thenReturn(3L);
        when(row.getLong(3)).thenReturn(4L);
        when(row.getString(1)).thenReturn("CLAIMED");
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });
        return jdbc;
    }

    private static DocumentVerificationRepository repository(JdbcTemplate jdbc) {
        ProxyFactory factory = new ProxyFactory(new JdbcDocumentVerificationRepository(jdbc));
        factory.addAdvice(new PersistenceExceptionTranslationInterceptor(
            EntityManagerFactoryUtils::convertJpaAccessExceptionIfPossible));
        return (DocumentVerificationRepository) factory.getProxy();
    }

    private static VerifiedContent content(String scanner) {
        return new VerifiedContent("application/pdf", 12, "a".repeat(64), scanner);
    }

    private static void assertFailure(String message, Runnable operation) {
        DocumentVerificationException failure = assertThrows(DocumentVerificationException.class, operation::run);
        assertEquals(message, failure.getMessage());
    }
}
