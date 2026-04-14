import { Client } from "@stomp/stompjs";
import { useEffect, useMemo, useState } from "react";
import { MarketDataBySymbol, PriceTick, SymbolOption } from "../types";

const SYMBOLS: SymbolOption[] = ["INFY", "TCS", "RELIANCE", "HDFC"];
const MARKET_DATA_ENDPOINT = "/api/market-data/api/v1/market-data";
const MARKET_DATA_WS_URL = "ws://localhost:8084/ws/market-data";

function createFallbackTick(symbol: SymbolOption): PriceTick {
  return {
    symbol,
    price: 0,
    change: 0,
    changePercent: 0,
    timestamp: new Date().toISOString(),
    volume: 0,
    vwap: 0
  };
}

export default function useMarketDataFeed() {
  const [latestTicks, setLatestTicks] = useState<MarketDataBySymbol>(() => ({
    INFY: createFallbackTick("INFY"),
    TCS: createFallbackTick("TCS"),
    RELIANCE: createFallbackTick("RELIANCE"),
    HDFC: createFallbackTick("HDFC")
  }));
  const [history, setHistory] = useState<Record<SymbolOption, number[]>>({
    INFY: [],
    TCS: [],
    RELIANCE: [],
    HDFC: []
  });
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    let alive = true;

    const seedInitialPrices = async () => {
      try {
        const response = await fetch(MARKET_DATA_ENDPOINT);
        if (!response.ok) return;
        const payload = (await response.json()) as MarketDataBySymbol;
        if (!alive) return;
        setLatestTicks((previous) => ({ ...previous, ...payload }));
        setHistory((previous) => {
          const next = { ...previous };
          SYMBOLS.forEach((symbol) => {
            const price = payload[symbol]?.price;
            if (typeof price === "number" && Number.isFinite(price)) {
              next[symbol] = [price];
            }
          });
          return next;
        });
      } catch {
        // Seed data is optional when websocket feed starts quickly.
      }
    };

    seedInitialPrices();

    const client = new Client({
      brokerURL: MARKET_DATA_WS_URL,
      reconnectDelay: 2000,
      onConnect: () => {
        if (!alive) return;
        setConnected(true);
        SYMBOLS.forEach((symbol) => {
          client.subscribe(`/topic/prices/${symbol}`, (message) => {
            try {
              const nextTick = JSON.parse(message.body) as PriceTick;
              if (!alive) return;
              setLatestTicks((previous) => ({ ...previous, [symbol]: nextTick }));
              setHistory((previous) => ({
                ...previous,
                [symbol]: [...previous[symbol], nextTick.price].slice(-20)
              }));
            } catch {
              // Ignore malformed frames and keep the stream alive.
            }
          });
        });
      },
      onWebSocketClose: () => {
        if (alive) {
          setConnected(false);
        }
      },
      onStompError: () => {
        if (alive) {
          setConnected(false);
        }
      }
    });

    client.activate();

    return () => {
      alive = false;
      setConnected(false);
      client.deactivate();
    };
  }, []);

  const symbolList = useMemo(() => SYMBOLS, []);

  return {
    latestTicks,
    history,
    connected,
    symbols: symbolList
  };
}
