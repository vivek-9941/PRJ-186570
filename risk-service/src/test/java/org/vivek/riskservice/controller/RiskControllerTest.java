package org.vivek.riskservice.controller;

import org.junit.jupiter.api.Test;
import org.vivek.riskservice.service.RiskServiceImpl;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskControllerTest {

    @Test
    void getPositionsReturnsPayloadFromService() {
        RiskServiceImpl riskService = mock(RiskServiceImpl.class);
        when(riskService.getPositions("U1")).thenReturn(Map.of("INFY", 100.0));
        RiskController controller = new RiskController(riskService);

        Map<String, Double> body = controller.getPositions("U1").getBody();

        assertNotNull(body);
        assertEquals(100.0, body.get("INFY"));
    }

    @Test
    void getRiskConfigReturnsServiceValues() {
        RiskServiceImpl riskService = mock(RiskServiceImpl.class);
        when(riskService.getRiskConfig()).thenReturn(Map.of("MAX_ORDER_VALUE", 500_000.0));
        RiskController controller = new RiskController(riskService);

        Map<String, Object> body = controller.getRiskConfig().getBody();

        assertNotNull(body);
        assertEquals(500_000.0, body.get("MAX_ORDER_VALUE"));
    }

    @Test
    void updatePositionDelegatesToServiceAndReturnsConfirmation() {
        RiskServiceImpl riskService = mock(RiskServiceImpl.class);
        RiskController controller = new RiskController(riskService);

        Map<String, Object> body = controller.updatePosition("U1", "INFY", 250.0).getBody();

        verify(riskService).updatePosition(eq("U1"), eq("INFY"), eq(250.0));
        assertNotNull(body);
        assertEquals("updated", body.get("status"));
        assertEquals("U1", body.get("userId"));
        assertEquals("INFY", body.get("symbol"));
        assertEquals(250.0, body.get("quantity"));
    }
}
