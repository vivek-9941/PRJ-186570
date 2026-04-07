import OrderPlacer from "./components/OrderPlacer";
import OrderTracker from "./components/OrderTracker";
import AnalyticsDashboard from "./components/AnalyticsDashboard";
import CircuitBreakerPanel from "./components/CircuitBreakerPanel";
import MarketDataBar from "./components/MarketDataBar";
import useMarketDataFeed from "./hooks/useMarketDataFeed";

function App() {
  const { latestTicks, history, connected, symbols } = useMarketDataFeed();

  return (
    <div className="min-h-screen text-slate-900">
      <header className="border-b border-slate-200/80 bg-white/90 backdrop-blur">
        <div className="mx-auto flex max-w-[1800px] items-center justify-between px-6 py-4">
          <h1 className="text-xl font-bold tracking-wide text-slate-800">
            Trade Orchestration Engine
          </h1>
          <div className="flex items-center gap-2 rounded-full bg-emerald-50 px-3 py-1 text-sm font-semibold text-emerald-700">
            <span className="h-2.5 w-2.5 animate-pulse rounded-full bg-emerald-500" />
            LIVE
          </div>
        </div>
      </header>

      <main className="mx-auto grid max-w-[1800px] grid-cols-1 gap-5 p-6 xl:grid-cols-3">
        <MarketDataBar latestTicks={latestTicks} history={history} symbols={symbols} connected={connected} />
        <CircuitBreakerPanel />
        <OrderPlacer latestTicks={latestTicks} />
        <OrderTracker />
        <AnalyticsDashboard />
      </main>
    </div>
  );
}

export default App;
