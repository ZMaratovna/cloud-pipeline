CREATE TABLE pipeline.cloud_profile_credentials_user (
    PRIMARY KEY (cloud_profile_credentials_id, user_id),
    cloud_profile_credentials_id integer NOT NULL REFERENCES pipeline.cloud_profile_credentials(id),
    user_id integer NOT NULL REFERENCES pipeline.user(id)
);
ALTER TABLE pipeline.user ADD COLUMN default_profile_id integer REFERENCES pipeline.cloud_profile_credentials(id) NULL;
