package nur.kg.cryptobot.service;

import lombok.RequiredArgsConstructor;
import nur.kg.cryptobot.client.MarketClient;
import nur.kg.cryptobot.dto.ProcessMarketRequestDto;
import org.springframework.stereotype.Service;

import java.util.Random;

@Service
@RequiredArgsConstructor
public class MarketService {

    private final MarketClient client;

    public void processMarket(ProcessMarketRequestDto request) {
        Random random = new Random();
        int r = random.nextInt(1, 10);

        if(r == 5) {
            client.processOrder(request.getSymbol(), request.getPrice());
        }
    }
}
