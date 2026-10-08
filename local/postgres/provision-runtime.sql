\set ON_ERROR_STOP on
\getenv runtime_password PEOPLECORE_RUNTIME_DB_PASSWORD
BEGIN;
SELECT format('CREATE ROLE peoplecore_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L', :'runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'peoplecore_runtime') \gexec
ALTER ROLE peoplecore_runtime NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
SELECT format('ALTER ROLE peoplecore_runtime PASSWORD %L', :'runtime_password') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO peoplecore_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON command_receipts, outbox_events, user_accounts, employees TO peoplecore_runtime;
REVOKE ALL ON audit_events FROM peoplecore_runtime;
GRANT SELECT, INSERT ON audit_events TO peoplecore_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO peoplecore_runtime;
COMMIT;
