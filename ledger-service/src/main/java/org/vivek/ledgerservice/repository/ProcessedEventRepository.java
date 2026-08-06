package org.vivek.ledgerservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.vivek.ledgerservice.entity.ProcessedEvent;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    boolean existsByEventIdAndEventType(String eventId, String eventType);
}
