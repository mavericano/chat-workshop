package com.leverx.chat.mcp;

import com.leverx.chat.common.ApiProblemException;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guards against server-side request forgery: users paste MCP URLs that chat-service connects to from inside the
 * Docker network, so only public https endpoints are accepted unless the host is explicitly allow-listed.
 */
@Component
public class McpUrlValidator {

    private static final Set<String> RESTRICTED_HEADERS =
            Set.of("host", "content-length", "connection", "upgrade", "expect", "transfer-encoding", "te", "trailer");

    private final Set<String> allowedHosts;

    public McpUrlValidator(McpProperties properties) {
        this.allowedHosts = properties.allowedHosts().stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Splits a validated URL into the base URL and the endpoint path expected by the MCP transports.
     */
    public McpTarget validate(String rawUrl, McpTransportType transport) {
        URI uri;
        try {
            uri = new URI(rawUrl.strip());
        } catch (URISyntaxException e) {
            throw ApiProblemException.badRequest("URL is not valid: " + e.getReason());
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw ApiProblemException.badRequest("URL must start with https://");
        }
        if (uri.getHost() == null) {
            throw ApiProblemException.badRequest("URL must contain a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw ApiProblemException.badRequest("Credentials in the URL are not allowed, use the auth header instead");
        }

        var host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(host)) {
            if (!scheme.equals("https")) {
                throw ApiProblemException.badRequest("Only https:// URLs are allowed");
            }
            ensurePublic(host);
        }

        var base = scheme + "://" + uri.getRawAuthority();
        var path = uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")
                ? transport.defaultEndpoint()
                : uri.getRawPath();
        var endpoint = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        return new McpTarget(base, endpoint);
    }

    public void validateHeader(String headerName) {
        if (headerName != null && RESTRICTED_HEADERS.contains(headerName.toLowerCase(Locale.ROOT))) {
            throw ApiProblemException.badRequest("Header '%s' cannot be set".formatted(headerName));
        }
    }

    private static void ensurePublic(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw ApiProblemException.badRequest("Cannot resolve host '%s'".formatted(host));
        }
        for (var address : addresses) {
            if (isInternal(address)) {
                throw ApiProblemException.badRequest(
                        "Host '%s' points to a private or local network address, which is not allowed".formatted(host));
            }
        }
    }

    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        var bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            return (bytes[0] & 0xfe) == 0xfc; // unique local fc00::/7
        }
        // carrier-grade NAT 100.64.0.0/10
        return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
    }

    public record McpTarget(String baseUrl, String endpoint) {
    }
}
