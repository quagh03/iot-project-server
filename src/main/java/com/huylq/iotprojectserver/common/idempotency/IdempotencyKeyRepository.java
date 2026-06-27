package com.huylq.iotprojectserver.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKeyId> {

  @Modifying
  @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :cutoff")
  int deleteExpired(@Param("cutoff") OffsetDateTime cutoff);
}
