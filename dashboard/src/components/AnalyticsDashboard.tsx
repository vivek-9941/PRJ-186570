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
    <section className="rounded-2xl border border-slate-200 bg-white/95 p-5 shadow-sm">
      <h2 className="text-lg font-semibold text-slate-800">ANALYTICS</h2>
      <p className="mt-1 text-sm text-slate-500">Auto-refreshes every 5 seconds.</p>

      {analyticsLoading && <p className="mt-4 text-sm font-medium text-slate-500">Loading analytics...</p>}
      {analyticsError && <p className="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">{analyticsError}</p>}

      <div className="mt-4 overflow-x-auto rounded-lg border border-slate-200">
        <table className="min-w-full border-collapse text-sm">
          <thead className="bg-slate-100 text-left text-xs uppercase tracking-wide text-slate-600">
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
              <tr><td className="px-3 py-3 text-center text-slate-500" colSpan={5}>No analytics data available yet.</td></tr>
            ) : (
              analyticsRows.map((row) => (
                <tr key={row.symbol} className="border-t border-slate-200 text-slate-700">
                  <td className="px-3 py-2 font-semibold">{row.symbol}</td>
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

      <div className="mt-4 h-64 rounded-lg border border-slate-200 bg-slate-50 p-2">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={analyticsRows}>
            <CartesianGrid strokeDasharray="3 3" stroke="#cbd5e1" />
            <XAxis dataKey="symbol" />
            <YAxis />
            <Tooltip />
            <Bar dataKey="totalVolume" fill="#16a34a" radius={[6, 6, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </section>
  );
}
