CREATE TABLE f03_ok.probe (
    id BIGINT PRIMARY KEY,
    value TEXT NOT NULL
);

INSERT INTO f03_ok.probe (id, value) VALUES (1, 'initial');
