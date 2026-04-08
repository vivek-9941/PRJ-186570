export type SymbolOption = "INFY" | "TCS" | "RELIANCE" | "HDFC";
export type Side = "BUY" | "SELL";

export interface PlaceOrderResponse {
  orderId: string;
  status: string;
  message?: string;
}

export interface OrderDetails {
  orderId: string;
  userId: string;
  symbol: string;
  side: Side;
  quantity: number;
  price: number;
  status: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface SymbolStats {
  totalTrades: number;
  totalVolume: number;
  lastPrice: number;
  avgPrice: number;
}

export interface AnalyticsRow extends SymbolStats {
  symbol: string;
}

export interface CircuitBreakerDetails {
  state: string;
  failureRate: string;
  calls?: number;
}

export type CircuitBreakerHealthResponse = Record<string, CircuitBreakerDetails>;

export interface TrackerStep {
  key: string;
  label: string;
  timestamp?: string;
  color: "green" | "amber" | "red" | "gray";
}

export interface PriceTick {
  symbol: SymbolOption;
  price: number;
  change: number;
  changePercent: number;
  timestamp: string;
  volume: number;
}

export type MarketDataBySymbol = Record<SymbolOption, PriceTick>;
