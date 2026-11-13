package id.mydev.peoplecore.common.command;

public interface CommandLockRepository {
    void lock(long lockKey, int lockTimeoutMs);
}
