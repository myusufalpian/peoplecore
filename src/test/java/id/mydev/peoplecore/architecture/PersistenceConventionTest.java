package id.mydev.peoplecore.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PersistenceConventionTest {
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern INLINE_QUALIFIED_NAME = Pattern.compile(
        "\\b(?:[a-z][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*");
    private static final Pattern ANNOTATION = Pattern.compile("@(?:[a-z][a-z0-9_]*\\.)+[A-Za-z_][\\w.]*");
    private static final Pattern NATIVE_SQL_CALL = Pattern.compile(
        "\\.\\s*(?:createNativeQuery|createNativeMutationQuery|createNativeSelectionQuery|createSQLQuery|getNamedNativeQuery|getNamedSQLQuery)\\s*\\(");

    @Test
    void annotationsAndTypesAreImportedInsteadOfWrittenFullyQualified() throws IOException {
        TreeSet<String> offenders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                int lineNumber = 0;
                for (String line : codeOnly(Files.readString(file)).split("\\R")) {
                    lineNumber++;
                    String trimmed = line.strip();
                    if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) {
                        continue;
                    }
                    if (ANNOTATION.matcher(line).find() || INLINE_QUALIFIED_NAME.matcher(line).find()) {
                        offenders.add(SOURCES.relativize(file) + ":" + lineNumber);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Use imports instead of fully qualified names: " + offenders);
    }

    @Test
    void applicationLayerDoesNotAccessSqlDirectly() throws IOException {
        TreeSet<String> withSql = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = SOURCES.relativize(file).toString();
                if (!relative.contains("/application/") && !relative.contains("/common/command/")) {
                    continue;
                }
                String code = codeOnly(Files.readString(file));
                if (NATIVE_SQL_CALL.matcher(code).find()) {
                    withSql.add(relative);
                }
                for (String line : code.split("\\R")) {
                    if (line.matches(".*(?:org[.]springframework[.]jdbc[.]|java[.]sql[.]|javax[.]sql[.]).*")) {
                        withSql.add(relative);
                    }
                }
            }
        }
        assertTrue(withSql.isEmpty(), "JDBC and native SQL belong in repository adapters: " + withSql);
    }

    @Test
    void nativeSqlGuardDetectsJpaAndHibernateCallsWithoutRejectingJpqlOrLiterals() {
        for (String method : new String[] {"createNativeQuery", "createNativeMutationQuery",
            "createNativeSelectionQuery", "createSQLQuery", "getNamedNativeQuery", "getNamedSQLQuery"}) {
            assertTrue(NATIVE_SQL_CALL.matcher(codeOnly("session.\n" + method + " (\"select 1\");")).find(), method);
        }
        String allowed = "entityManager.createQuery(\"select e from Employee e\");\n"
            + "String example = \"entityManager.createNativeQuery(\\\"select 1\\\")\";\n"
            + "// session.createSQLQuery(\"select 1\");\n"
            + "/* session.createNativeMutationQuery(\"delete from example\"); */\n"
            + "helper.recreateNativeQuery();";
        assertFalse(NATIVE_SQL_CALL.matcher(codeOnly(allowed)).find());
    }

    @Test
    void repositoriesDoNotDependOnApplicationModels() throws IOException {
        TreeSet<String> offenders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = SOURCES.relativize(file).toString();
                if (!relative.contains("/domain/repository/") && !relative.contains("/infrastructure/persistence/")) {
                    continue;
                }
                for (String line : codeOnly(Files.readString(file)).split("\\R")) {
                    if (line.strip().startsWith("import ") && line.contains(".application.")) {
                        offenders.add(relative);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Repository contracts and adapters must not depend on application models: " + offenders);
    }

    @Test
    void scannerIgnoresLiteralNamesButChecksCodeAfterTextBlocks() {
        String literals = "// java.sql.Connection\n/* java.sql.Timestamp */\n"
            + "String query = \"select new example.Record()\";";
        assertFalse(INLINE_QUALIFIED_NAME.matcher(codeOnly(literals)).find());
        String textBlock = "String query = \"\"\"\nselect new example.Record()\n\"\"\"; java.sql.Connection connection;";
        assertTrue(INLINE_QUALIFIED_NAME.matcher(codeOnly(textBlock)).find());
    }

    private static String codeOnly(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            if (source.startsWith("//", index)) {
                int end = source.indexOf('\n', index);
                index = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", index)) {
                int end = source.indexOf("*/", index + 2);
                int limit = end < 0 ? source.length() : end + 2;
                appendLineBreaks(source, index, limit, code);
                index = limit;
            } else if (source.startsWith("\"\"\"", index)) {
                int end = source.indexOf("\"\"\"", index + 3);
                int limit = end < 0 ? source.length() : end + 3;
                appendLineBreaks(source, index, limit, code);
                index = limit;
            } else if (source.charAt(index) == '"' || source.charAt(index) == '\'') {
                char delimiter = source.charAt(index++);
                code.append(' ');
                while (index < source.length()) {
                    char character = source.charAt(index++);
                    if (character == '\\' && index < source.length()) {
                        index++;
                    } else if (character == delimiter) {
                        break;
                    }
                }
            } else {
                code.append(source.charAt(index++));
            }
        }
        return code.toString();
    }

    private static void appendLineBreaks(String source, int start, int end, StringBuilder code) {
        code.append(' ');
        for (int index = start; index < end; index++) {
            if (source.charAt(index) == '\n') {
                code.append('\n');
            }
        }
    }
}
