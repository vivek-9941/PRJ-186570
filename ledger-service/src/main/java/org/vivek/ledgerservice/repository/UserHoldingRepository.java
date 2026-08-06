package org.vivek.ledgerservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.vivek.ledgerservice.entity.UserHolding;
import org.vivek.ledgerservice.entity.UserHoldingId;

import java.util.List;

@Repository
public interface UserHoldingRepository extends JpaRepository<UserHolding, UserHoldingId> {

    List<UserHolding> findByIdUserId(String userId);
}
