package nur.kg.cryptobot.controller;

import lombok.RequiredArgsConstructor;
import nur.kg.cryptobot.dto.ProcessMarketRequestDto;
import nur.kg.cryptobot.service.MarketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;

    @PostMapping("/process")
    public ResponseEntity<?> processMarket(ProcessMarketRequestDto requestDto) {

        marketService.processMarket(requestDto);

        return ResponseEntity.ok().build();
    }
}
