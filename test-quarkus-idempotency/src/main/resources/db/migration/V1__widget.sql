-- Rows written by WidgetService so the tests can see whether an operation's work committed or rolled back
CREATE TABLE IF NOT EXISTS ${flyway:defaultSchema}.widget (
    name VARCHAR(255) NOT NULL PRIMARY KEY,
    org_id VARCHAR(255) NOT NULL
);
