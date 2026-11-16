package id.mydev.peoplecore.common.outbox;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/event-replays")
@ConditionalOnProperty(name = "peoplecore.events.replay.enabled", havingValue = "true")
public class EventReplayController {
    public record ReplayRequest(@NotBlank String consumer, @NotBlank @Size(max = 1000) String reason,
                                @NotEmpty @Size(max = 100) List<@NotNull UUID> eventIds) { }
    public record Cancellation(@NotBlank @Size(max = 1000) String reason) { }
    private final EventReplayService service;

    public EventReplayController(EventReplayService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TransportRepository.Replay> request(Authentication caller,
        @RequestHeader("Idempotency-Key") String key, @RequestBody @Valid ReplayRequest request) {
        var replay = service.request(caller, key, request.consumer(), request.reason(), request.eventIds());
        return ResponseEntity.created(URI.create("/api/v1/operations/event-replays/" + replay.id())).body(replay);
    }

    @GetMapping("/{id}")
    public TransportRepository.Replay get(Authentication caller, @PathVariable UUID id) {
        return service.get(caller, id);
    }

    @PostMapping("/{id}/cancel")
    public TransportRepository.Replay cancel(Authentication caller, @PathVariable UUID id,
                                              @RequestBody @Valid Cancellation request) {
        return service.cancel(caller, id, request.reason());
    }
}
