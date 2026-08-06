package org.vivek.marginservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.vivek.marginservice.entity.MarginAccount;

@Repository
public interface MarginAccountRepository extends JpaRepository<MarginAccount, String> {
}
