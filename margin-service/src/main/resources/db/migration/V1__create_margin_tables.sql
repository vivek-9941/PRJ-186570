CREATE TABLE IF NOT EXISTS margin_account (
    user_id        VARCHAR(64)    NOT NULL,
    cash_balance   DECIMAL(15,2)  NOT NULL DEFAULT 0.00,
    holdings_value DECIMAL(15,2)  NOT NULL DEFAULT 0.00,
    reserved_margin DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    updated_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
                                  ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS order_reservation (
    order_id   VARCHAR(128)   NOT NULL,
    user_id    VARCHAR(64)    NOT NULL,
    amount     DECIMAL(15,2)  NOT NULL,
    created_at TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB;
