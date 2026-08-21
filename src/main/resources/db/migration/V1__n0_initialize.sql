-- N0 intentionally creates no domain tables.
-- Flyway records this migration in flyway_schema_history so an empty-database
-- startup can be verified before N1 introduces the first product schema.
SELECT 1;
