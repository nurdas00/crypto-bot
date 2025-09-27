package nur.kg.cryptobot.controller;

import lombok.RequiredArgsConstructor;
import nur.kg.cryptobot.service.MarketService;
import nur.kg.domain.dto.TickerDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@RestController
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;

    @PostMapping("/tickers/stream")
    public Mono<Void> stream(Flux<TickerDto> stream) {
        return marketService.processMarket(stream, Duration.ofMinutes(1));
    }
}
