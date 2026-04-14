import { useEffect, useState } from "react";
import OrderPlacer from "./components/OrderPlacer";
import OrderTracker from "./components/OrderTracker";
import AnalyticsDashboard from "./components/AnalyticsDashboard";
import CircuitBreakerPanel from "./components/CircuitBreakerPanel";
import MarketDataBar from "./components/MarketDataBar";
import OrderBookDepth from "./components/OrderBookDepth";
import FundsConsole from "./components/FundsConsole";
import useMarketDataFeed from "./hooks/useMarketDataFeed";
import { SymbolOption } from "./types";
import { getOrCreateClientUserId } from "./utils";

function App() {
  const { latestTicks, history, connected, symbols } = useMarketDataFeed();
  const [selectedSymbol, setSelectedSymbol] = useState<SymbolOption>("INFY");
  const [userId] = useState(() => getOrCreateClientUserId());

  useEffect(() => {
    if (!symbols.includes(selectedSymbol)) {
      setSelectedSymbol(symbols[0] ?? "INFY");
    }
  }, [selectedSymbol, symbols]);

  return (
    <div className="min-h-screen text-[#dae2fd]">
      <header className="border-b border-[#434656]/50 bg-[#0b1326]/90 backdrop-blur">
        <div className="mx-auto flex max-w-[1800px] items-center justify-between px-6 py-4">
          <h1 className="text-xl font-bold tracking-wide text-[#dae2fd]" style={{ fontFamily: "Manrope, Inter, sans-serif" }}>
            Trade Orchestration Engine
          </h1>
          <div className="flex items-center gap-3">
            <div className="rounded-full border border-[#434656]/70 bg-[#171f33] px-3 py-1 text-xs font-semibold text-[#c3c5d9]">
              {userId}
            </div>
            <div className="flex items-center gap-2 rounded-full border border-[#66d9cc]/35 bg-[#171f33] px-3 py-1 text-sm font-semibold text-[#66d9cc]">
              <span className="h-2.5 w-2.5 animate-pulse rounded-full bg-[#66d9cc]" />
              LIVE
            </div>
          </div>
        </div>
      </header>

      <main className="mx-auto grid max-w-[1800px] grid-cols-1 gap-5 p-6 xl:grid-cols-3">
        <MarketDataBar latestTicks={latestTicks} history={history} symbols={symbols} connected={connected} />
        <CircuitBreakerPanel />
        <OrderPlacer latestTicks={latestTicks} userId={userId} />
        <FundsConsole userId={userId} />
        <OrderBookDepth symbol={selectedSymbol} onSymbolChange={setSelectedSymbol} symbols={symbols} />
        <OrderTracker />
        <AnalyticsDashboard />
      </main>
    </div>
  );
}

export default App;
