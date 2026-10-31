package id.mydev.peoplecore.attendance.domain.exception;

public class WorkContextException extends RuntimeException {

    public static final String MISSING_SCHEDULE = "MISSING_SCHEDULE";
    public static final String OVERLAPPING_SCHEDULE = "OVERLAPPING_SCHEDULE";
    public static final String MISSING_CALENDAR = "MISSING_CALENDAR";
    public static final String OVERLAPPING_CALENDAR = "OVERLAPPING_CALENDAR";
    public static final String UNPUBLISHED_REVISION = "UNPUBLISHED_REVISION";
    public static final String NON_WORKING_DATE = "NON_WORKING_DATE";
    public static final String HOLIDAY_DATE = "HOLIDAY_DATE";
    public static final String ASSIGNMENT_STALE = "ASSIGNMENT_STALE";

    private final String reason;

    public WorkContextException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
