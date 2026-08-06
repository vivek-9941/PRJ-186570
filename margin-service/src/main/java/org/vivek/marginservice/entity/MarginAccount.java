package org.vivek.marginservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "margin_account")
public class MarginAccount {

    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "cash_balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal cashBalance;

    @Column(name = "holdings_value", nullable = false, precision = 15, scale = 2)
    private BigDecimal holdingsValue;

    @Column(name = "reserved_margin", nullable = false, precision = 15, scale = 2)
    private BigDecimal reservedMargin;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public MarginAccount() {
    }

    public MarginAccount(String userId, BigDecimal cashBalance, BigDecimal holdingsValue,
                         BigDecimal reservedMargin, Instant updatedAt) {
        this.userId = userId;
        this.cashBalance = cashBalance;
        this.holdingsValue = holdingsValue;
        this.reservedMargin = reservedMargin;
        this.updatedAt = updatedAt;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public BigDecimal getCashBalance() {
        return cashBalance;
    }

    public void setCashBalance(BigDecimal cashBalance) {
        this.cashBalance = cashBalance;
    }

    public BigDecimal getHoldingsValue() {
        return holdingsValue;
    }

    public void setHoldingsValue(BigDecimal holdingsValue) {
        this.holdingsValue = holdingsValue;
    }

    public BigDecimal getReservedMargin() {
        return reservedMargin;
    }

    public void setReservedMargin(BigDecimal reservedMargin) {
        this.reservedMargin = reservedMargin;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
