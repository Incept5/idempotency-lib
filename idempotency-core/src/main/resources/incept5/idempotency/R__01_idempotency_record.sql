-- This will run automatically only if flyway is told to look in incept5/migration as well as db/migration

-- Drop and re-create which is okay for this table since it's a cache
DROP TABLE IF EXISTS ${flyway:defaultSchema}.idempotency_records;
CREATE TABLE IF NOT EXISTS ${flyway:defaultSchema}.idempotency_records (
    key VARCHAR(255) NOT NULL,
    context VARCHAR(255) NOT NULL,
    request_hash VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    response TEXT,
    http_status_code INT,
    expires_at TIMESTAMP,
    PRIMARY KEY (key, context)
);
