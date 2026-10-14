package id.mydev.peoplecore.organization.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(name = "employee_contact_profile")
public class ContactProfile {

    public static final String GROUP_PHONE = "PHONE";
    public static final String GROUP_EMAIL = "EMAIL";
    public static final String GROUP_ADDRESS = "ADDRESS";
    public static final String GROUP_EMERGENCY = "EMERGENCY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false, unique = true)
    private Long employeeId;

    @Column(name = "personal_phone", length = 32)
    private String personalPhone;

    @Column(name = "personal_email", length = 254)
    private String personalEmail;

    @Column(name = "residential_address", length = 1000)
    private String residentialAddress;

    @Column(name = "emergency_contact_name", length = 200)
    private String emergencyContactName;

    @Column(name = "emergency_contact_relationship", length = 100)
    private String emergencyContactRelationship;

    @Column(name = "emergency_contact_phone", length = 32)
    private String emergencyContactPhone;

    @Column(name = "phone_version", nullable = false)
    private long phoneVersion;

    @Column(name = "email_version", nullable = false)
    private long emailVersion;

    @Column(name = "address_version", nullable = false)
    private long addressVersion;

    @Column(name = "emergency_version", nullable = false)
    private long emergencyVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContactProfile() {
    }

    public ContactProfile(Long employeeId, Instant now) {
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId must not be null");
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public void applyValue(String fieldName, String value, Instant now) {
        Objects.requireNonNull(fieldName, "fieldName must not be null");
        Objects.requireNonNull(now, "now must not be null");
        ProfileRevisionChange.requireKnownField(fieldName);
        Map<String, String> single = new HashMap<>();
        single.put(fieldName, value);
        applyChanges(single, now);
    }

    public void applyChanges(Map<String, String> changes, Instant now) {
        Objects.requireNonNull(changes, "changes must not be null");
        Objects.requireNonNull(now, "now must not be null");
        boolean phoneChanged = false;
        boolean emailChanged = false;
        boolean addressChanged = false;
        boolean emergencyChanged = false;
        for (Map.Entry<String, String> change : changes.entrySet()) {
            String fieldName = ProfileRevisionChange.requireKnownField(change.getKey());
            String value = change.getValue();
            switch (fieldName) {
                case ProfileRevisionChange.FIELD_PHONE -> {
                    if (!Objects.equals(personalPhone, value)) {
                        personalPhone = value;
                        phoneChanged = true;
                    }
                }
                case ProfileRevisionChange.FIELD_EMAIL -> {
                    if (!Objects.equals(personalEmail, value)) {
                        personalEmail = value;
                        emailChanged = true;
                    }
                }
                case ProfileRevisionChange.FIELD_ADDRESS -> {
                    if (!Objects.equals(residentialAddress, value)) {
                        residentialAddress = value;
                        addressChanged = true;
                    }
                }
                case ProfileRevisionChange.FIELD_EMERGENCY_NAME -> {
                    if (!Objects.equals(emergencyContactName, value)) {
                        emergencyContactName = value;
                        emergencyChanged = true;
                    }
                }
                case ProfileRevisionChange.FIELD_EMERGENCY_RELATIONSHIP -> {
                    if (!Objects.equals(emergencyContactRelationship, value)) {
                        emergencyContactRelationship = value;
                        emergencyChanged = true;
                    }
                }
                case ProfileRevisionChange.FIELD_EMERGENCY_PHONE -> {
                    if (!Objects.equals(emergencyContactPhone, value)) {
                        emergencyContactPhone = value;
                        emergencyChanged = true;
                    }
                }
                default -> throw new IllegalArgumentException("Unknown profile field: " + fieldName);
            }
        }
        if (phoneChanged) {
            phoneVersion++;
        }
        if (emailChanged) {
            emailVersion++;
        }
        if (addressChanged) {
            addressVersion++;
        }
        if (emergencyChanged) {
            emergencyVersion++;
        }
        if (phoneChanged || emailChanged || addressChanged || emergencyChanged) {
            updatedAt = now;
        }
    }

    public String valueOf(String fieldName) {
        return switch (Objects.requireNonNull(fieldName, "fieldName must not be null")) {
            case ProfileRevisionChange.FIELD_PHONE -> personalPhone;
            case ProfileRevisionChange.FIELD_EMAIL -> personalEmail;
            case ProfileRevisionChange.FIELD_ADDRESS -> residentialAddress;
            case ProfileRevisionChange.FIELD_EMERGENCY_NAME -> emergencyContactName;
            case ProfileRevisionChange.FIELD_EMERGENCY_RELATIONSHIP -> emergencyContactRelationship;
            case ProfileRevisionChange.FIELD_EMERGENCY_PHONE -> emergencyContactPhone;
            default -> throw new IllegalArgumentException("Unknown profile field: " + fieldName);
        };
    }

    public long versionOf(String group) {
        return switch (Objects.requireNonNull(group, "group must not be null")) {
            case GROUP_PHONE -> phoneVersion;
            case GROUP_EMAIL -> emailVersion;
            case GROUP_ADDRESS -> addressVersion;
            case GROUP_EMERGENCY -> emergencyVersion;
            default -> throw new IllegalArgumentException("Unknown profile group: " + group);
        };
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getPersonalPhone() {
        return personalPhone;
    }

    public String getPersonalEmail() {
        return personalEmail;
    }

    public String getResidentialAddress() {
        return residentialAddress;
    }

    public String getEmergencyContactName() {
        return emergencyContactName;
    }

    public String getEmergencyContactRelationship() {
        return emergencyContactRelationship;
    }

    public String getEmergencyContactPhone() {
        return emergencyContactPhone;
    }

    public long getPhoneVersion() {
        return phoneVersion;
    }

    public long getEmailVersion() {
        return emailVersion;
    }

    public long getAddressVersion() {
        return addressVersion;
    }

    public long getEmergencyVersion() {
        return emergencyVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
