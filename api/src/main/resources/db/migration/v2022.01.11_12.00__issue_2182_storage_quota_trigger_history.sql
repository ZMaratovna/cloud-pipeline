CREATE TABLE IF NOT EXISTS pipeline.last_triggered_storage_quota (
    storage_id BIGINT NOT NULL PRIMARY KEY,
    quota_value DOUBLE PRECISION NOT NULL,
    quota_type TEXT NOT NULL,
    actions JSONB NOT NULL,
    recipients JSONB NOT NULL,
    update_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() at time zone 'utc'),
    target_status TEXT NOT NULL DEFAULT 'ACTIVE',
    notification_required BOOLEAN DEFAULT TRUE NOT NULL,
    status_activation_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (now() at time zone 'utc'),
    CONSTRAINT datastorage_id_fk FOREIGN KEY (storage_id)
        REFERENCES pipeline.datastorage(datastorage_id) ON DELETE CASCADE
);
