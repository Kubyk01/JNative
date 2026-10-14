CREATE TABLE IF NOT EXISTS orders (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol     VARCHAR(16)     NOT NULL,
    side       VARCHAR(8)      NOT NULL,
    price      DECIMAL(19, 4)  NOT NULL,
    quantity   INT             NOT NULL,
    created_at TIMESTAMP       NOT NULL
);
