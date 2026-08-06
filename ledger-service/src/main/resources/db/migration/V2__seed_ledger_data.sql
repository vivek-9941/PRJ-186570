INSERT IGNORE INTO user_balance (user_id, balance) VALUES
    ('U1', 100000.00),
    ('U2', 250000.00),
    ('U3',  50000.00);

INSERT IGNORE INTO user_holding (user_id, symbol, quantity) VALUES
    ('U1', 'INFY',     8000.0000),
    ('U1', 'TCS',       500.0000),
    ('U1', 'RELIANCE',  200.0000),
    ('U2', 'TCS',      1000.0000);
