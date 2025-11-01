package nur.kg.cryptobot.controller;

import lombok.RequiredArgsConstructor;
import nur.kg.cryptobot.market.MarketService;
import nur.kg.domain.dto.TickerDto;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class MarketController {

    private final MarketService marketService;

    @PostMapping(value = "/tickers/stream", consumes = MediaType.APPLICATION_NDJSON_VALUE)
    public Mono<Void> stream(@RequestBody Flux<TickerDto> stream) {
        return marketService.processMarket(stream);
    }
}
