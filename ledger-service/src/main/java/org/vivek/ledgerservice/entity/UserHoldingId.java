package org.vivek.ledgerservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class UserHoldingId implements Serializable {

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "symbol", nullable = false, length = 16)
    private String symbol;

    public UserHoldingId() {
    }

    public UserHoldingId(String userId, String symbol) {
        this.userId = userId;
        this.symbol = symbol;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserHoldingId that = (UserHoldingId) o;
        return Objects.equals(userId, that.userId) && Objects.equals(symbol, that.symbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, symbol);
    }
}
