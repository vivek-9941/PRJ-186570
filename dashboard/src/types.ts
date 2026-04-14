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
  rejectionReason?: string;
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
  vwap: number;
}

export type MarketDataBySymbol = Record<SymbolOption, PriceTick>;

export interface OrderBookDepthLevel {
  price: number;
  quantity: number;
  orderCount: number;
}

export interface OrderBookDepth {
  symbol: SymbolOption;
  bids: OrderBookDepthLevel[];
  asks: OrderBookDepthLevel[];
  spread: number | null;
  midPrice: number | null;
}

export interface MarginSnapshot {
  cashBalance: number;
  holdingsValue: number;
  reservedMargin: number;
  availableMargin: number;
  totalNetworth: number;
}
