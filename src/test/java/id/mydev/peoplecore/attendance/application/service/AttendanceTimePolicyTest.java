package id.mydev.peoplecore.attendance.application.service;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import id.mydev.peoplecore.attendance.application.service.AttendanceTimePolicy.Action;

class AttendanceTimePolicyTest {
    private static final Instant START=Instant.parse("2026-10-12T02:00:00Z");
    private static final Instant END=START.plusSeconds(28800);
    @Test void clockWindowsAreInclusiveAndRejectOneMicrosecondBeyond() {
        assertTrue(AttendanceTimePolicy.accepts(Action.IN,START,END,START.minusSeconds(7200)));
        assertTrue(AttendanceTimePolicy.accepts(Action.IN,START,END,START.plusSeconds(7200)));
        assertFalse(AttendanceTimePolicy.accepts(Action.IN,START,END,START.plusSeconds(7200).plusNanos(1000)));
        assertFalse(AttendanceTimePolicy.accepts(Action.IN,START,END,START.minusSeconds(7200).minusNanos(1000)));
        assertTrue(AttendanceTimePolicy.accepts(Action.OUT,START,END,START));
        assertTrue(AttendanceTimePolicy.accepts(Action.OUT,START,END,END.plusSeconds(14400)));
        assertFalse(AttendanceTimePolicy.accepts(Action.OUT,START,END,END.plusSeconds(14400).plusNanos(1000)));
    }
    @Test void duplicateWindowIncludesExactlySixtySeconds() {
        assertTrue(AttendanceTimePolicy.duplicate(START,START.plusSeconds(60)));
        assertFalse(AttendanceTimePolicy.duplicate(START,START.plusSeconds(60).plusNanos(1000)));
        assertFalse(AttendanceTimePolicy.duplicate(START,START.minusNanos(1000)));
    }
    @Test void deadlineKeepsExactMicrosecondBoundary() {
        assertEquals(Instant.parse("2026-10-19T16:59:59.999999Z"),
            AttendanceTimePolicy.deadline(LocalDate.of(2026,10,12),"Asia/Jakarta"));
    }
    @Test void correctionsPreserveExistingActionsAndRequireAChange() {
        assertThrows(IllegalArgumentException.class,()->AttendanceTimePolicy.validatePair(START,END,
            START,null,START,END,END));
        assertThrows(IllegalArgumentException.class,()->AttendanceTimePolicy.validatePair(START,END,
            START,END,START,END,END));
        assertThrows(IllegalArgumentException.class,()->AttendanceTimePolicy.validatePair(START,END,
            START,START,null,null,END));
        AttendanceTimePolicy.validatePair(START,END,START,END,null,null,END);
    }
    @Test void correctionsRejectMissingFutureOutOfWindowAndUnorderedActions() {
        assertThrows(IllegalArgumentException.class,()->AttendanceTimePolicy.validatePair(START,END,
            null,END,null,null,END));
        assertThrows(IllegalArgumentException.class,()->AttendanceTimePolicy.validatePair(START,END,
            START.minusSeconds(7201),null,null,null,END));
        assertThrows(IllegalArgumentException.class,()->AttendanceTimePolicy.validatePair(START,END,
            START.plusSeconds(1),null,null,null,START));
        assertThrows(IllegalArgumentException.class,()->AttendanceTimePolicy.validatePair(START,END,
            START,END.plusSeconds(14401),null,null,END.plusSeconds(14401)));
        assertThrows(IllegalArgumentException.class,()->AttendanceTimePolicy.validatePair(START,END,
            START.plusSeconds(1),START,null,null,END));
        assertThrows(IllegalArgumentException.class,()->AttendanceTimePolicy.validatePair(START,END,
            START,END.plusSeconds(1),null,null,END));
        AttendanceTimePolicy.validatePair(START,END,START,END,START,null,END);
    }
}
