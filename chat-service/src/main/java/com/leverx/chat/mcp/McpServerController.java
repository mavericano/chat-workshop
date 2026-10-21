package com.leverx.chat.mcp;

import com.leverx.chat.mcp.McpServerDtos.McpServerPatch;
import com.leverx.chat.mcp.McpServerDtos.McpServerRequest;
import com.leverx.chat.mcp.McpServerDtos.McpServerResponse;
import com.leverx.chat.mcp.McpServerDtos.McpTestResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mcp/servers")
public class McpServerController {

    private final McpServerService service;

    public McpServerController(McpServerService service) {
        this.service = service;
    }

    @GetMapping
    public List<McpServerResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(userId(jwt));
    }

    @PostMapping("/test")
    public McpTestResult test(@Valid @RequestBody McpServerRequest request) {
        return service.test(request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public McpServerResponse create(@Valid @RequestBody McpServerRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.create(userId(jwt), request);
    }

    @PatchMapping("/{id}")
    public McpServerResponse patch(@PathVariable Long id, @Valid @RequestBody McpServerPatch patch,
                                   @AuthenticationPrincipal Jwt jwt) {
        return service.setEnabled(userId(jwt), id, patch.enabled());
    }

    @PostMapping("/{id}/reconnect")
    public McpServerResponse reconnect(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return service.reconnect(userId(jwt), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(userId(jwt), id);
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
