package com.huylq.iotprojectserver.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceImplTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-25T10:30:00Z");

  @Mock private AuditLogRepository repo;

  private AuditServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new AuditServiceImpl(repo);
  }

  private static AuditLog row(long id, OffsetDateTime ts) {
    return AuditLog.builder().id(id).ts(ts).actor("usr_1").actorType(AuditLog.ActorType.USER)
        .event("user.login").build();
  }

  @Test
  void query_reports_no_more_when_page_not_full() {
    Page<AuditLog> page = new PageImpl<>(List.of(row(1, NOW)));
    when(repo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

    AuditPage result = service.query(null, null, null, null, NOW.minusDays(1), NOW, null, 50);

    assertThat(result.items()).hasSize(1);
    assertThat(result.hasMore()).isFalse();
    assertThat(result.nextCursor()).isNull();
  }

  @Test
  void query_reports_hasMore_and_a_decodable_cursor_when_extra_row_fetched() {
    AuditLog row1 = row(3, NOW);
    AuditLog row2 = row(2, NOW.minusMinutes(1));
    AuditLog extra = row(1, NOW.minusMinutes(2));
    Page<AuditLog> page = new PageImpl<>(List.of(row1, row2, extra));
    when(repo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

    AuditPage result = service.query(null, null, null, null, NOW.minusDays(1), NOW, null, 2);

    assertThat(result.items()).containsExactly(row1, row2);
    assertThat(result.hasMore()).isTrue();
    assertThat(result.nextCursor()).isNotNull();
    AuditCursor decoded = AuditCursor.decode(result.nextCursor());
    assertThat(decoded.ts()).isEqualTo(row2.getTs());
    assertThat(decoded.id()).isEqualTo(row2.getId());
  }

  @Test
  void query_decodes_the_supplied_cursor_before_querying() {
    Page<AuditLog> page = new PageImpl<>(List.of());
    when(repo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
    String cursor = new AuditCursor(NOW, 5L).encode();

    AuditPage result = service.query(null, null, null, null, NOW.minusDays(1), NOW, cursor, 50);

    assertThat(result.items()).isEmpty();
  }
}
