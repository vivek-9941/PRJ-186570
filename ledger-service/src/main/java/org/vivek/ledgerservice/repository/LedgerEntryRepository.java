package org.vivek.ledgerservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.vivek.ledgerservice.entity.LedgerEntryEntity;

import java.util.List;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, String> {

    List<LedgerEntryEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<LedgerEntryEntity> findByTradeId(String tradeId);
}
