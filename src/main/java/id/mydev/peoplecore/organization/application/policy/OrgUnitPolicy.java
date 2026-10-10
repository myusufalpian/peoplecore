package id.mydev.peoplecore.organization.application.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import id.mydev.peoplecore.organization.domain.exception.UnknownOrgUnitException;
@Component
public class OrgUnitPolicy {
    private final Set<String> allowed;

    public OrgUnitPolicy(@Value("${peoplecore.organization.allowed-units:}") String units) {
        this.allowed = Arrays.stream(units.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .collect(Collectors.toSet());
    }

    public void requireAllowed(String orgUnit) {
        if (allowed.isEmpty()) {
            throw new IllegalStateException("Organization units are not configured");
        }
        if (orgUnit == null || !allowed.contains(orgUnit)) {
            throw new UnknownOrgUnitException("Organization unit is not recognized");
        }
    }
}
