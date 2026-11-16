package id.mydev.peoplecore.reporting.api;

import id.mydev.peoplecore.reporting.application.ReportSnapshot;
import id.mydev.peoplecore.reporting.application.ReportType;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportDtoMapperTest {
    private final ReportDtoMapper mapper=new ReportDtoMapper();
    private final ReportSnapshot snapshot=new ReportSnapshot(ReportType.ATTENDANCE,1,"filter",List.of("employeeId","employeeName"),
        List.of(List.of("one",""),List.of("two","")),Instant.parse("2026-11-13T00:00:00Z"),3,null);

    @Test
    void cursorKeepsFiltersBoundAndMarksUnavailableSourceColumn() {
        var first=mapper.page(snapshot,parameters(1,null));
        var metadata=(ReportDtoMapper.Metadata)first.metadata();
        assertEquals(2,metadata.totalRows());
        assertEquals(List.of("employeeName"),metadata.unavailableColumns());
        assertEquals("two",mapper.page(snapshot,parameters(1,metadata.nextCursor())).data().getFirst().get("employeeId"));
        var changed=new ReportSnapshot(ReportType.ATTENDANCE,1,"changed",snapshot.columns(),snapshot.rows(),snapshot.dataAsOf(),3,null);
        assertThrows(IllegalArgumentException.class,()->mapper.page(changed,parameters(1,metadata.nextCursor())));
    }

    @Test
    void invalidCursorAndPageSizeFailExplicitly() {
        assertThrows(IllegalArgumentException.class,()->mapper.page(snapshot,parameters(101,null)));
        assertThrows(IllegalArgumentException.class,()->mapper.page(snapshot,parameters(1,"invalid")));
    }
    @Test
    void forgedAndExpiredCursorOffsetsCannotBypassTheSelectedResultBound() {
        for(String value:List.of("filter:-1","filter:10001","filter:3","filter:not-a-number","filter:1:extra")) {
            String cursor=Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class,()->mapper.page(snapshot,parameters(1,cursor)));
        }
        assertThrows(IllegalArgumentException.class,()->mapper.page(snapshot,parameters(0,null)));
        assertThrows(IllegalArgumentException.class,()->mapper.page(snapshot,parameters(1,"x".repeat(257))));
        assertThrows(IllegalArgumentException.class,()->mapper.request(ReportType.ATTENDANCE,parameters(0,null)));
        assertThrows(IllegalArgumentException.class,()->mapper.request(ReportType.ATTENDANCE,parameters(1,"x".repeat(257))));
        assertEquals(1,mapper.request(ReportType.ATTENDANCE,parameters(1,"cursor")).reportVersion());
    }

    @Test
    void emptyCatalogAndDefaultPageSizeExposeNoFabricatedUnavailableColumns() {
        var empty=new ReportSnapshot(ReportType.AUDIT,1,"audit",List.of("eventId"),List.of(),snapshot.dataAsOf(),3,null);
        var page=mapper.page(empty,parameters(null,null));
        assertEquals(List.of(),page.data());
        assertEquals(List.of(),((ReportDtoMapper.Metadata)page.metadata()).unavailableColumns());
        assertEquals(2,mapper.page(snapshot,parameters(null,null)).data().size());
    }

    private static ReportParameters parameters(Integer size,String cursor) {
        return new ReportParameters(null,null,null,null,null,null,null,null,null,null,null,null,null,size,cursor);
    }
}
