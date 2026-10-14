package id.mydev.peoplecore.common.command;

public interface AdmissionGuard {

    enum Mode {
        SHARED,
        EXCLUSIVE
    }

    Mode modeForCommand(String commandType);

    void acquire(Mode mode);
}
