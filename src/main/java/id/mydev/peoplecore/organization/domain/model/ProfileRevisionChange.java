package id.mydev.peoplecore.organization.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "profile_revision_change")
public class ProfileRevisionChange {

    public static final String FIELD_PHONE = "personalPhone";
    public static final String FIELD_EMAIL = "personalEmail";
    public static final String FIELD_ADDRESS = "residentialAddress";
    public static final String FIELD_EMERGENCY_NAME = "emergencyContactName";
    public static final String FIELD_EMERGENCY_RELATIONSHIP = "emergencyContactRelationship";
    public static final String FIELD_EMERGENCY_PHONE = "emergencyContactPhone";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "field_name", nullable = false, length = 64)
    private String fieldName;

    @Column(name = "baseline_value", length = 1000)
    private String baselineValue;

    @Column(name = "proposed_value", nullable = false, length = 1000)
    private String proposedValue;

    @Column(name = "baseline_version", nullable = false)
    private long baselineVersion;

    protected ProfileRevisionChange() {
    }

    public ProfileRevisionChange(Long revisionId, String fieldName, String baselineValue,
                                 String proposedValue, long baselineVersion) {
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId must not be null");
        this.fieldName = requireKnownField(fieldName);
        this.baselineValue = baselineValue;
        this.proposedValue = Objects.requireNonNull(proposedValue, "proposedValue must not be null");
        this.baselineVersion = baselineVersion;
    }

    public static String requireKnownField(String fieldName) {
        if (!isKnownField(fieldName)) {
            throw new IllegalArgumentException("Unknown profile field: " + fieldName);
        }
        return fieldName;
    }

    public static boolean isKnownField(String fieldName) {
        return FIELD_PHONE.equals(fieldName) || FIELD_EMAIL.equals(fieldName)
            || FIELD_ADDRESS.equals(fieldName) || FIELD_EMERGENCY_NAME.equals(fieldName)
            || FIELD_EMERGENCY_RELATIONSHIP.equals(fieldName) || FIELD_EMERGENCY_PHONE.equals(fieldName);
    }

    public static boolean isEmergencyField(String fieldName) {
        return FIELD_EMERGENCY_NAME.equals(fieldName)
            || FIELD_EMERGENCY_RELATIONSHIP.equals(fieldName)
            || FIELD_EMERGENCY_PHONE.equals(fieldName);
    }

    public static String groupOf(String fieldName) {
        if (FIELD_PHONE.equals(fieldName)) {
            return ContactProfile.GROUP_PHONE;
        }
        if (FIELD_EMAIL.equals(fieldName)) {
            return ContactProfile.GROUP_EMAIL;
        }
        if (FIELD_ADDRESS.equals(fieldName)) {
            return ContactProfile.GROUP_ADDRESS;
        }
        if (isEmergencyField(fieldName)) {
            return ContactProfile.GROUP_EMERGENCY;
        }
        throw new IllegalArgumentException("Unknown profile field: " + fieldName);
    }

    public Long getId() {
        return id;
    }

    public Long getRevisionId() {
        return revisionId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getBaselineValue() {
        return baselineValue;
    }

    public String getProposedValue() {
        return proposedValue;
    }

    public long getBaselineVersion() {
        return baselineVersion;
    }
}
