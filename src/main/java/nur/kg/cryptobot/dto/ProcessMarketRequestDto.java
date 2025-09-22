package nur.kg.cryptobot.dto;

import lombok.Getter;
import lombok.Setter;
import nur.kg.cryptobot.enums.Symbol;

@Getter
@Setter
public class ProcessMarketRequestDto {
    private Symbol symbol;
    private Float price;
}