package nur.kg.cryptobot.controller;

import lombok.RequiredArgsConstructor;
import nur.kg.cryptobot.service.MarketService;
import nur.kg.domain.dto.TickerDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;

    @PostMapping("/process")
    public Mono<Void> processMarket(Flux<TickerDto> requestDto) {

        marketService.processMarket(requestDto);

    }
}
