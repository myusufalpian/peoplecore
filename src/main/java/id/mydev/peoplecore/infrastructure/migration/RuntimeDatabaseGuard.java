package id.mydev.peoplecore.infrastructure.migration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.sql.SQLException;

@Component
public class RuntimeDatabaseGuard implements InitializingBean {
    private final DataSource dataSource;

    public RuntimeDatabaseGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                return;
            }
        }
        var jdbc = new JdbcTemplate(dataSource);
        Boolean unsafe = jdbc.queryForObject("""
            select exists (select 1 from pg_roles where rolname=current_user
                and (rolsuper or rolcreaterole or rolcreatedb or rolreplication))
                or exists (select 1 from pg_roles where (rolsuper or rolcreaterole or rolcreatedb or rolreplication)
                    and pg_has_role(current_user,oid,'MEMBER'))
                or exists (select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
                    where n.nspname=current_schema() and pg_has_role(current_user,c.relowner,'MEMBER'))
                or has_schema_privilege(current_schema(),'CREATE')
                or has_table_privilege('audit_events','UPDATE,DELETE,TRUNCATE')
            """, Boolean.class);
        Assert.state(Boolean.FALSE.equals(unsafe), "Runtime database role must use least privilege and cannot own schema objects or mutate audit events");
    }
}
