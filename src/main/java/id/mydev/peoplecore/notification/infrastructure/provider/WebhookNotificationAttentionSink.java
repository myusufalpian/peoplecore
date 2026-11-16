package id.mydev.peoplecore.notification.infrastructure.provider;

import id.mydev.peoplecore.notification.domain.NotificationAttentionSink;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

public class WebhookNotificationAttentionSink implements NotificationAttentionSink, AutoCloseable {
    private final URI endpoint;
    private final String token;
    private final ObjectMapper mapper;
    private final HttpClient client;
    public WebhookNotificationAttentionSink(URI endpoint,String token,ObjectMapper mapper) {
        if(endpoint==null) throw new IllegalArgumentException("A notification alert endpoint is required");
        boolean loopback=Set.of("localhost","127.0.0.1","[::1]").contains(String.valueOf(endpoint.getHost()));
        if(endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getFragment()!=null
                || endpoint.getQuery()!=null || !("https".equals(endpoint.getScheme()) || loopback&&"http".equals(endpoint.getScheme()))
                || !StringUtils.hasText(token) || token.length()>4096 || token.chars().anyMatch(c->c<=32 || c>=127))
            throw new IllegalArgumentException("An authenticated HTTPS notification alert endpoint is required");
        this.endpoint=endpoint; this.token=token; this.mapper=mapper;
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public void send(Attention attention) {
        HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2))
            .header("Authorization","Bearer "+token).header("Idempotency-Key",attention.id().toString())
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(attention))).build();
        try {
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()) {
                int status=response.statusCode();
                if(status<200 || status>=300) throw new IllegalStateException("Notification alert was not acknowledged");
            }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Notification alert interrupted");
        } catch(IOException failure) {
            throw new IllegalStateException("Notification alert unavailable");
        }
    }
    @Override public void close() { client.shutdownNow(); }
}
