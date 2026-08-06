INSERT IGNORE INTO margin_account
    (user_id, cash_balance, holdings_value, reserved_margin)
VALUES
    ('U1', 100000.00, 150000.00, 0.00),
    ('U2', 250000.00,  80000.00, 0.00),
    ('U3',  50000.00,      0.00, 0.00);
