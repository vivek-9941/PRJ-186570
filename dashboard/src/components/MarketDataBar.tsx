import { MarketDataBySymbol, PriceTick, SymbolOption } from "../types";

interface MarketDataBarProps {
  latestTicks: MarketDataBySymbol;
  history: Record<SymbolOption, number[]>;
  symbols: SymbolOption[];
  connected: boolean;
}

function formatPrice(value: number): string {
  return `Rs ${value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function trendClass(change: number): string {
  if (change > 0) return "text-[#84f5e8]";
  if (change < 0) return "text-[#ffb4ab]";
  return "text-[#c3c5d9]";
}

function sparklinePoints(values: number[]): string {
  if (values.length <= 1) {
    return "0,18 100,18";
  }

  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;

  return values
    .map((value, index) => {
      const x = (index / (values.length - 1)) * 100;
      const y = 18 - ((value - min) / range) * 16;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join(" ");
}

function Sparkline({ tick, values }: { tick: PriceTick; values: number[] }) {
  const stroke = tick.change >= 0 ? "#66d9cc" : "#ffb4ab";
  const points = sparklinePoints(values.length ? values : [tick.price]);

  return (
    <svg viewBox="0 0 100 20" className="h-8 w-full overflow-visible">
      <polyline points={points} fill="none" stroke={stroke} strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

export default function MarketDataBar({ latestTicks, history, symbols, connected }: MarketDataBarProps) {
  return (
    <section className="rounded-2xl border border-[#434656]/70 bg-gradient-to-r from-[#131b2e] via-[#171f33] to-[#222a3d] p-4 shadow-sm xl:col-span-3">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-sm font-semibold tracking-[0.18em] text-[#66d9cc]">LIVE MARKET DATA</h2>
        <div className={`rounded-full border px-2.5 py-1 text-xs font-semibold ${connected ? "border-[#66d9cc]/35 bg-[#1ea296]/20 text-[#84f5e8]" : "border-[#ffb870]/35 bg-[#955700]/20 text-[#ffdcbe]"}`}>
          {connected ? "WebSocket Connected" : "Reconnecting..."}
        </div>
      </div>

      <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {symbols.map((symbol) => {
          const tick = latestTicks[symbol];
          const changeSign = tick.change > 0 ? "+" : "";

          return (
            <article key={symbol} className="rounded-xl border border-[#434656]/70 bg-[#0b1326]/70 px-4 py-3 backdrop-blur-sm">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-bold tracking-wider text-[#b7c4ff]">{symbol}</h3>
                <span className={trendClass(tick.change)}>{changeSign}{tick.changePercent.toFixed(2)}%</span>
              </div>
              <p className="mt-1 font-mono text-2xl font-bold tracking-tight text-[#dae2fd]">{formatPrice(tick.price)}</p>
              <p className={`text-sm font-semibold ${trendClass(tick.change)}`}>
                {changeSign}Rs {tick.change.toFixed(2)} ({changeSign}{tick.changePercent.toFixed(2)}%)
              </p>
              <p className="mt-0.5 text-xs font-medium text-[#c3c5d9]">VWAP {formatPrice(tick.vwap)}</p>
              <div className="mt-2">
                <Sparkline tick={tick} values={history[symbol]} />
              </div>
            </article>
          );
        })}
      </div>
    </section>
  );
}
