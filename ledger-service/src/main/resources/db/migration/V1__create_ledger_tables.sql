CREATE TABLE IF NOT EXISTS user_balance (
    user_id     VARCHAR(64)    NOT NULL,
    balance     DECIMAL(15,2)  NOT NULL DEFAULT 0.00,
    updated_at  TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
                               ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS user_holding (
    user_id    VARCHAR(64)    NOT NULL,
    symbol     VARCHAR(16)    NOT NULL,
    quantity   DECIMAL(15,4)  NOT NULL DEFAULT 0.0000,
    updated_at TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
                              ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, symbol)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ledger_entry (
    entry_id      VARCHAR(128)   NOT NULL,
    trade_id      VARCHAR(128),
    user_id       VARCHAR(64)    NOT NULL,
    side          VARCHAR(8)     NOT NULL,
    symbol        VARCHAR(16)    NOT NULL,
    quantity      DECIMAL(15,4)  NOT NULL,
    price         DECIMAL(15,2)  NOT NULL,
    amount        DECIMAL(15,2)  NOT NULL,
    balance_after DECIMAL(15,2)  NOT NULL,
    created_at    TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (entry_id),
    INDEX idx_user_id (user_id),
    INDEX idx_trade_id (trade_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS processed_event (
    event_id    VARCHAR(128)  NOT NULL,
    event_type  VARCHAR(32)   NOT NULL,
    processed_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id)
) ENGINE=InnoDB;
