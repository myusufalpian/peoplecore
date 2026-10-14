package id.mydev.peoplecore.approval.domain.validation;

public final class RouteBlockReasons {

    public static final String NO_MATCH = "NO_MATCH";
    public static final String AMBIGUOUS_RULE = "AMBIGUOUS_RULE";
    public static final String NO_DESIGNATED_REVIEWER = "NO_DESIGNATED_REVIEWER";
    public static final String REVIEWER_INELIGIBLE = "REVIEWER_INELIGIBLE";
    public static final String SELF_REVIEW_PROHIBITED = "SELF_REVIEW_PROHIBITED";
    public static final String NO_ACTIVE_CONFIGURATION = "NO_ACTIVE_CONFIGURATION";

    private RouteBlockReasons() {
    }
}
