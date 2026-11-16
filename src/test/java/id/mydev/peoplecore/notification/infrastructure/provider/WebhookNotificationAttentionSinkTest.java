package id.mydev.peoplecore.notification.infrastructure.provider;

import com.sun.net.httpserver.HttpServer;
import id.mydev.peoplecore.notification.domain.NotificationAttentionSink.Attention;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookNotificationAttentionSinkTest {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    @Test void sendsOnlySafeMetadataWithStableIdentityAndDoesNotFollowRedirects() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var body=new AtomicReference<String>(); var authorization=new AtomicReference<String>(); var key=new AtomicReference<String>();
        var status=new AtomicInteger(204); var redirected=new AtomicInteger();
        server.createContext("/alerts",exchange->{
            body.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.getResponseHeaders().set("Location","/redirected");
            exchange.sendResponseHeaders(status.get(),-1); exchange.close();
        });
        server.createContext("/redirected",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});
        server.start();
        try(var sink=new WebhookNotificationAttentionSink(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/alerts"),"test-channel-token",MAPPER)) {
            var attention=new Attention(UUID.randomUUID(),UUID.randomUUID(),null,"RECIPIENT_UNAVAILABLE",Instant.parse("2026-11-14T00:00:00Z"));
            sink.send(attention);
            assertEquals("Bearer test-channel-token",authorization.get()); assertEquals(attention.id().toString(),key.get());
            assertEquals(5,MAPPER.readTree(body.get()).size()); assertFalse(body.get().contains("token"));
            status.set(503); assertThrows(IllegalStateException.class,()->sink.send(attention));
            status.set(302); assertThrows(IllegalStateException.class,()->sink.send(attention)); assertEquals(0,redirected.get());
        } finally { server.stop(0); }
    }
    @Test void rejectsUntrustedEndpointFormsAndInvalidAuthorizationConfiguration() {
        for(String url:List.of("http://monitor.example.test/alert","https://user:pass@monitor.example.test/alert",
                "https://monitor.example.test/alert?token=secret","https://monitor.example.test/alert#fragment","file:///tmp/alert"))
            assertThrows(IllegalArgumentException.class,()->new WebhookNotificationAttentionSink(URI.create(url),"valid-token",MAPPER));
        for(String token:List.of(""," ","bad\nvalue","has space","x".repeat(4097)))
            assertThrows(IllegalArgumentException.class,()->new WebhookNotificationAttentionSink(URI.create("https://monitor.example.test/alert"),token,MAPPER));
        try(var ignored=new WebhookNotificationAttentionSink(URI.create("https://monitor.example.test/alert"),"valid-token",MAPPER)) { }
    }
}
