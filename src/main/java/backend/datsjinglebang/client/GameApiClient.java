package backend.datsjinglebang.client;

import backend.datsjinglebang.model.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class GameApiClient {
    private final WebClient client;

    public GameApiClient(WebClient client) {
        this.client = client;
    }

    // Получение состояния арены
    public Mono<ArenaResponse> getArena() {
        return client.get()
                .uri("/arena")
                .retrieve()
                .bodyToMono(ArenaResponse.class);
    }

    // Отправка команд движения
    public Mono<Void> move(MoveRequest request) {
        return client.post()
                .uri("/move")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Void.class);
    }

    // Получение информации о бустерах
    public Mono<BoosterResponse> getBoosters() {
        return client.get()
                .uri("/booster")
                .retrieve()
                .bodyToMono(BoosterResponse.class);
    }

    // НОВЫЙ МЕТОД: Покупка бустера
    public Mono<Void> purchaseBooster(PurchaseBoosterRequest request) {
        return client.post()
                .uri("/booster")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Void.class);
    }

    // Применение бустера (возможно, это то же самое что и purchaseBooster)
    public Mono<ApplyBoosterResponse> applyBooster(String boosterType) {
        ApplyBoosterRequest request = new ApplyBoosterRequest();
        request.setBooster(boosterType);

        return client.post()
                .uri("/booster")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ApplyBoosterResponse.class);
    }

    // Получение логов
    public Mono<List<LogEntry>> getLogs() {
        return client.get()
                .uri("/logs")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<LogEntry>>() {});
    }

    // Получение информации о раундах
    public Mono<RoundsResponse> getRounds() {
        return client.get()
                .uri("/rounds")
                .retrieve()
                .bodyToMono(RoundsResponse.class);
    }
}