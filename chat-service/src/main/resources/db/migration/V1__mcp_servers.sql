-- MCP servers that users connect to the chat from the chat UI
CREATE TABLE mcp_server
(
    id                     BIGSERIAL PRIMARY KEY,
    owner_id               BIGINT        NOT NULL,
    name                   VARCHAR(40)   NOT NULL,
    url                    VARCHAR(2000) NOT NULL,
    transport              VARCHAR(20)   NOT NULL,
    enabled                BOOLEAN       NOT NULL DEFAULT TRUE,
    header_name            VARCHAR(100),
    -- AES-GCM encrypted, never returned by the API
    header_value_encrypted VARCHAR(4000),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT mcp_server_owner_name_uk UNIQUE (owner_id, name)
);
