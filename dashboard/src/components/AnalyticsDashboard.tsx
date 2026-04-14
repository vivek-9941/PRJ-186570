import { useState, useEffect } from "react";
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { AnalyticsRow, SymbolStats } from "../types";
import { formatNumber, parseError } from "../utils";

const ANALYTICS_ENDPOINT = "/api/analytics/api/v1/analytics/symbols";

export default function AnalyticsDashboard() {
  const [analyticsRows, setAnalyticsRows] = useState<AnalyticsRow[]>([]);
  const [analyticsLoading, setAnalyticsLoading] = useState(true);
  const [analyticsError, setAnalyticsError] = useState("");

  useEffect(() => {
    let alive = true;
    const fetchAnalytics = async () => {
      try {
        const response = await fetch(ANALYTICS_ENDPOINT);
        if (!response.ok) throw new Error(await parseError(response));
        const payload = (await response.json()) as Record<string, SymbolStats> | null;
        if (!alive) return;
        const mappedRows = Object.entries(payload ?? {})
          .map(([entrySymbol, stats]) => ({
            symbol: entrySymbol,
            totalTrades: stats.totalTrades,
            totalVolume: stats.totalVolume,
            lastPrice: stats.lastPrice,
            avgPrice: stats.avgPrice
          }))
          .sort((a, b) => a.symbol.localeCompare(b.symbol));
        setAnalyticsRows(mappedRows);
        setAnalyticsError("");
      } catch (error) {
        if (!alive) return;
        setAnalyticsError(error instanceof Error ? error.message : "Unable to load analytics right now.");
      } finally {
        if (alive) setAnalyticsLoading(false);
      }
    };

    fetchAnalytics();
    const intervalId = window.setInterval(fetchAnalytics, 5000);
    return () => { alive = false; window.clearInterval(intervalId); };
  }, []);

  return (
    <section className="panel">
      <h2 className="panel-title">ANALYTICS</h2>
      <p className="panel-subtitle">Auto-refreshes every 5 seconds.</p>

      {analyticsLoading && <p className="mt-4 text-sm font-medium text-[#c3c5d9]">Loading analytics...</p>}
      {analyticsError && <p className="mt-4 rounded-lg border border-[#ffb4ab]/35 bg-[#93000a]/35 px-3 py-2 text-sm text-[#ffdad6]">{analyticsError}</p>}

      <div className="mt-4 overflow-x-auto rounded-lg border border-[#434656]/85 bg-[#0b1326]/55">
        <table className="min-w-full border-collapse text-sm">
          <thead className="bg-[#171f33] text-left text-xs uppercase tracking-wide text-[#c3c5d9]">
            <tr>
              <th className="px-3 py-2">Symbol</th>
              <th className="px-3 py-2">Total Trades</th>
              <th className="px-3 py-2">Total Volume</th>
              <th className="px-3 py-2">Last Price</th>
              <th className="px-3 py-2">Avg Price</th>
            </tr>
          </thead>
          <tbody>
            {analyticsRows.length === 0 && !analyticsLoading ? (
              <tr>
                <td className="px-3 py-3 text-center text-[#8d90a2]" colSpan={5}>
                  No analytics data yet. Place matching BUY/SELL orders (for example `U2` BUY and `U1` SELL on the same symbol/price) to generate executed trades.
                </td>
              </tr>
            ) : (
              analyticsRows.map((row) => (
                <tr key={row.symbol} className="border-t border-[#434656]/65 text-[#dae2fd]">
                  <td className="px-3 py-2 font-semibold text-[#b7c4ff]">{row.symbol}</td>
                  <td className="px-3 py-2">{formatNumber(row.totalTrades)}</td>
                  <td className="px-3 py-2">{formatNumber(row.totalVolume)}</td>
                  <td className="px-3 py-2">{formatNumber(row.lastPrice)}</td>
                  <td className="px-3 py-2">{formatNumber(row.avgPrice)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div className="mt-4 h-64 rounded-lg border border-[#434656]/85 bg-[#0b1326]/65 p-2">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={analyticsRows}>
            <CartesianGrid strokeDasharray="3 3" stroke="#434656" />
            <XAxis dataKey="symbol" stroke="#c3c5d9" />
            <YAxis stroke="#c3c5d9" />
            <Tooltip
              cursor={{ fill: "rgba(183, 196, 255, 0.08)" }}
              contentStyle={{ background: "#171f33", border: "1px solid #434656", color: "#dae2fd" }}
              labelStyle={{ color: "#b7c4ff" }}
            />
            <Bar dataKey="totalVolume" fill="#66d9cc" radius={[6, 6, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </section>
  );
}
