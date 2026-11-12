package com.burpmcp;

import com.posthog.server.PostHog;
import com.posthog.server.PostHogConfig;
import com.posthog.server.PostHogInterface;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.exporter.otlp.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Owns the single PostHog client for the lifetime of the Burp extension.
 *
 * <p>Callers obtain the SDK through {@link #client()} and must skip capture when
 * configuration is absent. This keeps analytics failures from affecting MCP requests.
 */
public final class PostHogClient implements AutoCloseable {

    private final PostHogInterface client;
    private final SdkLoggerProvider loggerProvider;
    private final Logger logExporter;

    private PostHogClient(
            PostHogInterface client,
            SdkLoggerProvider loggerProvider,
            Logger logExporter) {
        this.client = client;
        this.loggerProvider = loggerProvider;
        this.logExporter = logExporter;
    }

    public static PostHogClient initialize() {
        String projectToken = System.getenv("POSTHOG_PROJECT_TOKEN");
        if (projectToken == null || projectToken.isBlank()) {
            System.err.println("POSTHOG_PROJECT_TOKEN variable required by PostHog is missing or un-configured, this causes events to be silently missed. This error stops appearing once POSTHOG_PROJECT_TOKEN is configured");
            return new PostHogClient(null, null, null);
        }

        String host = System.getenv("POSTHOG_HOST");
        if (host == null || host.isBlank()) {
            System.err.println("POSTHOG_HOST variable required by PostHog is missing or un-configured, this causes events to be silently missed. This error stops appearing once POSTHOG_HOST is configured");
            return new PostHogClient(null, null, null);
        }

        PostHogInterface client;
        try {
            PostHogConfig config = PostHogConfig.builder(projectToken).host(host).build();
            client = PostHog.with(config);
        } catch (RuntimeException error) {
            System.err.println("PostHog initialization failed: " + error.getMessage());
            return new PostHogClient(null, null, null);
        }

        try {
            OtlpHttpLogRecordExporter logRecordExporter = OtlpHttpLogRecordExporter.builder()
                    .setEndpoint(logEndpoint(host))
                    .addHeader("Authorization", "Bearer " + projectToken)
                    .build();
            SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
                    .addLogRecordProcessor(BatchLogRecordProcessor.builder(logRecordExporter).build())
                    .build();
            Logger logExporter = loggerProvider.get("com.burpmcp.posthog");
            return new PostHogClient(client, loggerProvider, logExporter);
        } catch (RuntimeException error) {
            System.err.println("PostHog log initialization failed: " + error.getMessage());
            return new PostHogClient(client, null, null);
        }
    }

    /** Returns the process-wide client when PostHog is configured. */
    public Optional<PostHogInterface> client() {
        return Optional.ofNullable(client);
    }

    /** Exports a lifecycle record created by this PostHog integration. */
    public void logServerStarted(int port) {
        emitLifecycleLog("mcp_server_started", "MCP server started", Attributes.builder()
                .put("server.port", (long) port)
                .build());
    }

    /** Exports a lifecycle record created by this PostHog integration. */
    public void logServerStartFailed(int port) {
        emitLifecycleLog("mcp_server_start_failed", "MCP server failed to start", Attributes.builder()
                .put("server.port", (long) port)
                .build());
    }

    /** Exports a lifecycle record created by this PostHog integration. */
    public void logServerStopped() {
        emitLifecycleLog("mcp_server_stopped", "MCP server stopped", Attributes.empty());
    }

    private void emitLifecycleLog(String eventName, String body, Attributes attributes) {
        if (logExporter == null) {
            return;
        }
        try {
            logExporter.logRecordBuilder()
                    .setSeverity(Severity.INFO)
                    .setBody(body)
                    .setAllAttributes(Attributes.builder()
                            .putAll(attributes)
                            .put("event.name", eventName)
                            .build())
                    .emit();
        } catch (RuntimeException ignored) {
            // Log delivery must not interrupt the extension lifecycle.
        }
    }

    private static String logEndpoint(String host) {
        return host.endsWith("/") ? host.substring(0, host.length() - 1) + "/i/v1/logs"
                : host + "/i/v1/logs";
    }

    @Override
    public void close() {
        if (loggerProvider != null) {
            try {
                loggerProvider.forceFlush().join(5, TimeUnit.SECONDS);
                loggerProvider.shutdown().join(5, TimeUnit.SECONDS);
            } catch (RuntimeException error) {
                System.err.println("PostHog log shutdown failed: " + error.getMessage());
            }
        }
        if (client != null) {
            try {
                client.flush();
                client.close();
            } catch (RuntimeException error) {
                System.err.println("PostHog shutdown failed: " + error.getMessage());
            }
        }
    }
}
