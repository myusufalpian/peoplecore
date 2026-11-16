package id.mydev.peoplecore.reportexport.application;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

public final class ReportCsvWriter {
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    public byte[] render(List<String> columns, List<List<String>> rows) {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        append(target, columns);
        for (List<String> row : rows) {
            if (row.size() != columns.size()) throw new IllegalArgumentException("Report row does not match columns");
            append(target, row);
        }
        return target.toByteArray();
    }

    private static void append(ByteArrayOutputStream target, List<String> values) {
        String line = values.stream().map(ReportCsvWriter::cell).collect(Collectors.joining(",")) + "\r\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES - target.size()) throw new IllegalArgumentException("Export artifact exceeds maximum size");
        target.writeBytes(bytes);
    }

    static String cell(String value) {
        String text = value == null ? "" : value;
        int start=0;
        while(start<text.length() && (text.charAt(start)<=' ' || Character.isWhitespace(text.charAt(start))
            || Character.isSpaceChar(text.charAt(start)) || text.charAt(start)=='\ufeff')) start++;
        boolean formula=start<text.length() && "=+-@".indexOf(text.charAt(start))>=0;
        boolean control=!text.isEmpty() && text.charAt(0)<' ';
        if(formula || control) text = "'" + text;
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
