package org.vivek.marginservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.vivek.marginservice.entity.OrderReservation;

import java.util.Optional;

@Repository
public interface OrderReservationRepository extends JpaRepository<OrderReservation, String> {

    void deleteByOrderId(String orderId);

    Optional<OrderReservation> findByOrderId(String orderId);
}
