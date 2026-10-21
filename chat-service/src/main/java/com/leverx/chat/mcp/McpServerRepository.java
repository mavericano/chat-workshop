package com.leverx.chat.mcp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpServerRepository extends JpaRepository<McpServer, Long> {

    List<McpServer> findByOwnerIdOrderByName(Long ownerId);

    List<McpServer> findByOwnerIdAndEnabledTrueOrderByName(Long ownerId);

    Optional<McpServer> findByIdAndOwnerId(Long id, Long ownerId);

    boolean existsByOwnerIdAndNameIgnoreCase(Long ownerId, String name);

    long countByOwnerId(Long ownerId);
}
