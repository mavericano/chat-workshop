package com.leverx.chat.mcp;

import com.leverx.chat.common.ApiProblemException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpUrlValidatorTest {

    private final McpUrlValidator validator = validator(List.of("mcp-internal"));

    @Test
    void splitsPublicHttpsUrlIntoBaseAndEndpoint() {
        var target = validator.validate("https://1.1.1.1:8443/v1/mcp?tenant=a", McpTransportType.STREAMABLE_HTTP);

        assertThat(target.baseUrl()).isEqualTo("https://1.1.1.1:8443");
        assertThat(target.endpoint()).isEqualTo("/v1/mcp?tenant=a");
    }

    @Test
    void usesDefaultEndpointOfTransportWhenUrlHasNoPath() {
        assertThat(validator.validate("https://1.1.1.1", McpTransportType.STREAMABLE_HTTP).endpoint()).isEqualTo("/mcp");
        assertThat(validator.validate("https://1.1.1.1/", McpTransportType.SSE).endpoint()).isEqualTo("/sse");
    }

    @Test
    void rejectsPlainHttpForPublicHosts() {
        assertThatThrownBy(() -> validator.validate("http://1.1.1.1/mcp", McpTransportType.STREAMABLE_HTTP))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("https");
    }

    @Test
    void rejectsPrivateAndLocalAddresses() {
        for (var url : List.of("https://127.0.0.1/mcp", "https://localhost/mcp", "https://10.1.2.3/mcp",
                "https://192.168.0.10/mcp", "https://172.20.0.5/mcp", "https://169.254.169.254/latest",
                "https://[::1]/mcp", "https://[fd00::1]/mcp", "https://100.64.0.1/mcp")) {
            assertThatThrownBy(() -> validator.validate(url, McpTransportType.STREAMABLE_HTTP))
                    .as(url)
                    .isInstanceOf(ApiProblemException.class)
                    .hasMessageContaining("private or local");
        }
    }

    @Test
    void allowsListedInternalHostsEvenOverHttp() {
        var target = validator.validate("http://mcp-internal:8080/mcp", McpTransportType.STREAMABLE_HTTP);

        assertThat(target.baseUrl()).isEqualTo("http://mcp-internal:8080");
    }

    @Test
    void rejectsOtherSchemesAndCredentialsInUrl() {
        assertThatThrownBy(() -> validator.validate("file:///etc/passwd", McpTransportType.SSE))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> validator.validate("https://user:secret@1.1.1.1/mcp", McpTransportType.SSE))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("Credentials");
    }

    @Test
    void rejectsRestrictedHeaders() {
        assertThatThrownBy(() -> validator.validateHeader("Host")).isInstanceOf(ApiProblemException.class);
        validator.validateHeader("Authorization");
    }

    private static McpUrlValidator validator(List<String> allowedHosts) {
        return new McpUrlValidator(new McpProperties(10, 64, Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofMinutes(30), allowedHosts, new McpProperties.Secrets("key", "5f2c8a91d4e7b036")));
    }
}
