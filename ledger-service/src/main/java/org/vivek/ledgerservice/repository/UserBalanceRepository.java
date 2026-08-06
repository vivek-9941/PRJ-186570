package org.vivek.ledgerservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.vivek.ledgerservice.entity.UserBalance;

@Repository
public interface UserBalanceRepository extends JpaRepository<UserBalance, String> {
}
