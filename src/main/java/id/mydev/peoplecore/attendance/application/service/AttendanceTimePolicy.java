package id.mydev.peoplecore.attendance.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

public final class AttendanceTimePolicy {
    public enum Action { IN, OUT }
    private AttendanceTimePolicy() { }

    public static boolean accepts(Action action, Instant start, Instant end, Instant acceptedAt) {
        Instant first = action == Action.IN ? start.minusSeconds(7200) : start;
        Instant last = action == Action.IN ? start.plusSeconds(7200) : end.plusSeconds(14400);
        return !acceptedAt.isBefore(first) && !acceptedAt.isAfter(last);
    }

    public static boolean duplicate(Instant original, Instant attempted) {
        return !attempted.isBefore(original) && !attempted.isAfter(original.plusSeconds(60));
    }

    public static Instant deadline(LocalDate workDate, String timezone) {
        return workDate.plusDays(7).atTime(LocalTime.of(23, 59, 59, 999999000))
            .atZone(ZoneId.of(timezone)).toInstant();
    }

    public static void validatePair(Instant start, Instant end, Instant proposedIn, Instant proposedOut,
                                    Instant existingIn, Instant existingOut, Instant now) {
        if (proposedIn == null || (existingOut != null && proposedOut == null)) {
            throw new IllegalArgumentException("Existing evidence cannot be removed and IN is required");
        }
        if (!accepts(Action.IN, start, end, proposedIn) || proposedIn.isAfter(now)) {
            throw new IllegalArgumentException("Proposed IN is outside its accepted window");
        }
        if (proposedOut != null && (!accepts(Action.OUT, start, end, proposedOut)
            || !proposedOut.isAfter(proposedIn) || proposedOut.isAfter(now))) {
            throw new IllegalArgumentException("Proposed OUT is outside its accepted window or order");
        }
        if (proposedIn.equals(existingIn) && java.util.Objects.equals(proposedOut, existingOut)) {
            throw new IllegalArgumentException("Correction must change or add an action");
        }
    }
}
