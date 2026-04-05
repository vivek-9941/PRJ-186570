package org.vivek.riskservice.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.vivek.commonmodule.model.TradeExecution;
import org.vivek.riskservice.service.RiskServiceImpl;

@Component
@Slf4j
@RequiredArgsConstructor
public class TradeExecutionListener {

    private final RiskServiceImpl riskService;

    @KafkaListener(
            topics = "trade-executed",
            groupId = "risk-group",
            properties = {
                    "spring.json.value.default.type=org.vivek.commonmodule.model.TradeExecution"
            }
    )
    public void onTradeExecuted(TradeExecution trade) {
        log.info("Received trade execution: tradeId={} symbol={} qty={} price={}",
                trade.getTradeId(), trade.getSymbol(), trade.getQuantity(), trade.getExecutedPrice());

        // For buyer: increase position by quantity
        if (trade.getBuyerId() != null) {
            riskService.adjustPosition(trade.getBuyerId(), trade.getSymbol(), trade.getQuantity());
            log.info("Buyer position updated: userId={} symbol={} +{}",
                    trade.getBuyerId(), trade.getSymbol(), trade.getQuantity());
        }

        // For seller: decrease position by quantity
        if (trade.getSellerId() != null) {
            riskService.adjustPosition(trade.getSellerId(), trade.getSymbol(), -trade.getQuantity());
            log.info("Seller position updated: userId={} symbol={} -{}",
                    trade.getSellerId(), trade.getSymbol(), trade.getQuantity());
        }
    }
}
