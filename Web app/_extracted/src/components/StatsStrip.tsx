import type { Item, Sale } from "../types";
import { inr, isToday } from "../lib/format";
import { AlertIcon, CartIcon, RupeeIcon, TrendUpIcon } from "./icons";

interface Props {
  sales: Sale[];
  items: Item[];
}

export default function StatsStrip({ sales, items }: Props) {
  const today = sales.filter((s) => isToday(s.time));
  const revenue = today.reduce((a, s) => a + s.amount, 0);
  const profit = today.reduce((a, s) => a + s.profit, 0);
  const units = today.reduce((a, s) => a + s.qty, 0);
  const lowStock = items.filter((i) => i.stock <= 10).length;

  const cards = [
    {
      label: "आज की बिक्री",
      sub: "Today's revenue",
      value: inr(revenue),
      icon: RupeeIcon,
      tone: "text-stone-900",
      chip: "bg-emerald-100 text-emerald-800",
    },
    {
      label: "आज का मुनाफ़ा",
      sub: "Today's profit",
      value: inr(profit),
      icon: TrendUpIcon,
      tone: profit >= 0 ? "text-emerald-700" : "text-red-600",
      chip: profit >= 0 ? "bg-emerald-100 text-emerald-800" : "bg-red-100 text-red-700",
    },
    {
      label: "बिक्री entries",
      sub: `${units} units sold`,
      value: String(today.length),
      icon: CartIcon,
      tone: "text-stone-900",
      chip: "bg-amber-100 text-amber-800",
    },
    {
      label: "कम stock items",
      sub: lowStock > 0 ? "Restock जल्दी करें" : "All healthy",
      value: String(lowStock),
      icon: AlertIcon,
      tone: lowStock > 0 ? "text-amber-700" : "text-stone-900",
      chip: lowStock > 0 ? "bg-amber-100 text-amber-800" : "bg-stone-100 text-stone-600",
    },
  ];

  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      {cards.map((c) => (
        <div
          key={c.label}
          className="rounded-2xl border border-stone-200 bg-white p-4 shadow-sm"
        >
          <div className="flex items-center justify-between">
            <span className={`rounded-lg p-2 ${c.chip}`}>
              <c.icon className="h-4 w-4" />
            </span>
          </div>
          <p className={`font-display mt-3 text-2xl font-bold ${c.tone}`}>{c.value}</p>
          <p className="text-xs font-bold text-stone-700">{c.label}</p>
          <p className="text-[11px] text-stone-400">{c.sub}</p>
        </div>
      ))}
    </div>
  );
}
