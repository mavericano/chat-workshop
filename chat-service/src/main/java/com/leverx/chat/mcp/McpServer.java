package com.leverx.chat.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "mcp_server")
public class McpServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Id of the user (from auth-service) who added the server; servers are private to their owner. */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(nullable = false, length = 2000)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private McpTransportType transport;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "header_name", length = 100)
    private String headerName;

    @Column(name = "header_value_encrypted", length = 4000)
    private String headerValueEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected McpServer() {
    }

    public McpServer(Long ownerId, String name, String url, McpTransportType transport,
                     String headerName, String headerValueEncrypted) {
        this.ownerId = ownerId;
        this.name = name;
        this.url = url;
        this.transport = transport;
        this.headerName = headerName;
        this.headerValueEncrypted = headerValueEncrypted;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public McpTransportType getTransport() {
        return transport;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getHeaderValueEncrypted() {
        return headerValueEncrypted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
