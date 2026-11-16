package id.mydev.peoplecore.reporting.api;

import id.mydev.peoplecore.common.api.ApiData;
import id.mydev.peoplecore.reporting.application.ReportRequest;
import id.mydev.peoplecore.reporting.application.ReportSnapshot;
import id.mydev.peoplecore.reporting.application.ReportType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ReportDtoMapper {
    public ReportRequest request(ReportType type,ReportParameters p) {
        if(p.size()!=null&&(p.size()<1||p.size()>100)) throw new IllegalArgumentException("Page size must be between 1 and 100");
        if(p.cursor()!=null&&p.cursor().length()>256) throw new IllegalArgumentException("Invalid report cursor");
        return new ReportRequest(type,1,p.from(),p.to(),p.year(),p.month(),p.employeeId(),p.unit(),p.status(),
            p.workflowType(),p.resourceId(),p.actorId(),p.action(),p.correlationId(),p.category());
    }

    public ApiData<List<Map<String,String>>> page(ReportSnapshot snapshot,ReportParameters p) {
        int size=p.size()==null?50:p.size();
        if(size<1||size>100) throw new IllegalArgumentException("Page size must be between 1 and 100");
        int offset=offset(p.cursor(),snapshot.filterHash());
        if(offset>snapshot.rows().size()) throw new IllegalArgumentException("Cursor exceeds current result; refresh the report");
        int end=Math.min(offset+size,snapshot.rows().size());
        List<Map<String,String>> rows=new ArrayList<>();
        for(List<String> values:snapshot.rows().subList(offset,end)) {
            Map<String,String> row=new LinkedHashMap<>();
            for(int i=0;i<snapshot.columns().size();i++) row.put(snapshot.columns().get(i),values.get(i));
            rows.add(Map.copyOf(row));
        }
        String next=end<snapshot.rows().size()?Base64.getUrlEncoder().withoutPadding().encodeToString(
            (snapshot.filterHash()+":"+end).getBytes(StandardCharsets.UTF_8)):null;
        return new ApiData<>(List.copyOf(rows),new Metadata(snapshot.reportVersion(),snapshot.filterHash(),snapshot.dataAsOf(),
            "Asia/Jakarta",false,snapshot.rows().size(),next,snapshot.columns().contains("employeeName")?List.of("employeeName"):List.of()));
    }

    private static int offset(String cursor,String filterHash) {
        if(cursor==null) return 0;
        if(cursor.length()>256) throw new IllegalArgumentException("Invalid report cursor");
        try {
            String decoded=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
            String[] parts=decoded.split(":",-1);
            if(parts.length!=2||!parts[0].equals(filterHash)) throw new IllegalArgumentException("Cursor filters changed");
            int offset=Integer.parseInt(parts[1]);
            if(offset<0||offset>10_000) throw new IllegalArgumentException("Invalid report cursor offset");
            return offset;
        } catch(NumberFormatException ex) { throw new IllegalArgumentException("Invalid report cursor"); }
    }

    public record Metadata(int reportVersion,String filterHash,Instant dataAsOf,String timezone,boolean historical,
                           int totalRows,String nextCursor,List<String> unavailableColumns) { }
}
