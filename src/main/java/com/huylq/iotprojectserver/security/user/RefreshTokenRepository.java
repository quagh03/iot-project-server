package com.huylq.iotprojectserver.security.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  @Modifying
  @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.user.id = :userId AND r.revoked = false")
  int revokeAllForUser(@Param("userId") UUID userId);

  // "now" is passed in rather than using the JPQL now()/CURRENT_TIMESTAMP function so
  // tests can use Clocks' fixed-clock seam, and so Hibernate has a concrete type to
  // compare expiresAt against.
  @Query("SELECT r FROM RefreshToken r WHERE r.user.id = :userId AND r.revoked = false AND r.expiresAt > :now")
  List<RefreshToken> findAllActiveByUserId(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);

}
