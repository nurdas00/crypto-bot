package nur.kg.cryptobot.market;

import nur.kg.domain.dto.TickerDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface MarketService {
    Mono<Void> processMarket(Flux<TickerDto> ticks);
}
