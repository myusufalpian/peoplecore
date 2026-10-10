package id.mydev.peoplecore.common.command;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.function.Supplier;

public final class CommandRetry {
    private static final int MAX_ATTEMPTS = 3;

    private CommandRetry() { }

    public static <T> T withRetry(Supplier<T> command) {
        int attempt = 0;
        while (true) {
            try {
                return command.get();
            } catch (ObjectOptimisticLockingFailureException | PessimisticLockingFailureException ex) {
                attempt++;
                if (attempt >= MAX_ATTEMPTS) {
                    throw ex;
                }
            }
        }
    }
}
