package com.microservicios.auth.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvento, String> {
    @Query(value = "SELECT * FROM outbox_evento WHERE publicado_en IS NULL "
            + "ORDER BY creado_en, id LIMIT :limite FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvento> bloquearPendientes(@Param("limite") int limite);
    long countByPublicadoEnIsNull();
}
