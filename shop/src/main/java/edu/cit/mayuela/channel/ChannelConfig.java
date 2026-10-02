package edu.cit.mayuela.channel;

import edu.cit.mayuela.platform.AppInstance;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
/**
 * Builds the one HTTP client the channel owns.
 *
 * Three headers are attached to every single call, here once instead of at each
 * call site: the student id, the bearer key, and this JVM's instance id. A
 * Tiangge request can therefore never go out unidentified, including retries.
 *
 * Timeouts are explicit and short on purpose - a marketplace that hangs must
 * not be able to hold up an order transaction. 5s to connect, 10s to read.
 */
@Configuration
@EnableConfigurationProperties(ChannelProperties.class)
class ChannelConfig {

    private static final org.slf4j.Logger log = ChannelLogger.get();

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    RestClient tianggeRestClient(ChannelProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);

        // No explicit message converters: RestClient picks up the JSON mapper the
        // application already has, which keeps the channel on one wire format.
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("X-Client-Id", properties.getClientId())
                .defaultHeader("X-Client-Instance", AppInstance.instanceId());

        // Sent only when a key exists. A blank placeholder must not turn into a
        // "Bearer " header that Tiangge would reject as malformed credentials.
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + properties.getApiKey().trim());
        } else {
            // Without this the symptom is a bare "401 invalid_credentials" on
            // every call, which looks like a bad key rather than a missing one.
            log.warn("No channel.api-key configured, so no Authorization header will be sent; "
                    + "Tiangge will reject every call. Set the TIANGGE_API_KEY environment "
                    + "variable and make sure the process that starts the application inherits it.");
        }
        return builder.build();
    }
}