package id.mydev.peoplecore.reportexport.application;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportCsvWriterTest {
    private final ReportCsvWriter csv=new ReportCsvWriter();

    @Test
    void quotedCsvPreservesUnicodeNewlinesAndEscapesEmbeddedQuotes() {
        String rendered=new String(csv.render(List.of("name","status"),List.of(List.of("Áyu,\"B\"\nC","READY"))),StandardCharsets.UTF_8);
        assertEquals("\"name\",\"status\"\r\n\"Áyu,\"\"B\"\"\nC\",\"READY\"\r\n",rendered);
    }

    @Test
    void employeeTextCannotBecomeSpreadsheetFormulaAfterWhitespace() {
        for(String value:List.of("=1+1","+cmd","-2+3","@SUM(A1)","   =2","\ttext","\rline","\nline","\ufeff=1","\u00a0=1","\u0000=1"))
            assertTrue(ReportCsvWriter.cell(value).startsWith("\"'"));
        assertEquals("\"ordinary\"",ReportCsvWriter.cell("ordinary"));
        assertEquals("\"\"",ReportCsvWriter.cell(null));
        assertEquals("\"   \"",ReportCsvWriter.cell("   "));
    }

    @Test
    void mismatchedColumnsAndOversizedArtifactsNeverReturnPartialCsv() {
        assertThrows(IllegalArgumentException.class,()->csv.render(List.of("a","b"),List.of(List.of("only one"))));
        assertThrows(IllegalArgumentException.class,()->csv.render(List.of("a"),List.of(List.of("x".repeat(ReportCsvWriter.MAX_BYTES)))));
    }

    @Test
    void absentCellsRemainEmptyData() {
        assertEquals("\"value\"\r\n\"\"\r\n",new String(csv.render(List.of("value"),List.of(Arrays.asList((String)null))),StandardCharsets.UTF_8));
    }
}
