CREATE TABLE app_user
(
    id               UUID         NOT NULL,
    user_number      BIGINT       NOT NULL,
    email_normalized VARCHAR(254) NOT NULL,
    password_hash    VARCHAR(255) NOT NULL,
    role             VARCHAR(16)  NOT NULL,
    active           BOOLEAN      NOT NULL,
    version          BIGINT       NOT NULL,
    created_at       TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (id)
);