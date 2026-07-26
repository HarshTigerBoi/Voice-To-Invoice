import { useState } from "react";
import type { Item, Unit } from "../types";
import { formatQty, inr, unitLabel, UNIT_LABELS } from "../lib/format";
import { AlertIcon, BoxIcon, PlusIcon, TrashIcon } from "./icons";

interface Props {
  items: Item[];
  onAdd: (item: Item) => void;
  onDelete: (id: string) => void;
  onRestock: (id: string, amount: number) => void;
  toast: (msg: string, kind?: "ok" | "warn" | "err") => void;
}

const LOW_STOCK = 10;

export default function InventoryView({ items, onAdd, onDelete, onRestock, toast }: Props) {
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState({
    name: "",
    altName: "",
    unit: "pkt" as Unit,
    stock: "",
    cost: "",
    sell: "",
  });

  const submit = () => {
    const sell = parseFloat(form.sell);
    if (!form.name.trim()) return toast("Item का नाम चाहिए", "err");
    if (isNaN(sell) || sell <= 0) return toast("बिक्री भाव (sell price) डालें", "err");
    const cost = parseFloat(form.cost);
    onAdd({
      id: "u" + Date.now(),
      name: form.name.trim(),
      altName: form.altName.trim(),
      unit: form.unit,
      stock: parseFloat(form.stock) || 0,
      costPrice: isNaN(cost) ? sell : cost,
      sellPrice: sell,
    });
    setForm({ name: "", altName: "", unit: "pkt", stock: "", cost: "", sell: "" });
    setShowForm(false);
    toast(`"${form.name.trim()}" inventory में जुड़ गया`, "ok");
  };

  const lowCount = items.filter((i) => i.stock <= LOW_STOCK).length;

  return (
    <section className="overflow-hidden rounded-2xl border border-stone-200 bg-white shadow-sm">
      <div className="flex items-center justify-between border-b border-stone-100 px-5 py-4">
        <div className="flex items-center gap-2.5">
          <BoxIcon className="h-5 w-5 text-emerald-700" />
          <h2 className="font-display text-lg font-bold text-stone-900">स्टॉक</h2>
          {lowCount > 0 && (
            <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-bold text-amber-800">
              {lowCount} कम
            </span>
          )}
        </div>
        <button
          onClick={() => setShowForm((v) => !v)}
          className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-700 px-3 py-1.5 text-xs font-bold text-white hover:bg-emerald-800"
        >
          <PlusIcon className="h-3.5 w-3.5" /> Item
        </button>
      </div>

      {showForm && (
        <div className="border-b border-stone-100 bg-stone-50/70 p-4">
          <div className="grid grid-cols-2 gap-2.5">
            <input
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="नाम (e.g. बिस्कुट)"
              className="col-span-2 rounded-lg border border-stone-200 bg-white px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none"
            />
            <input
              value={form.altName}
              onChange={(e) => setForm({ ...form, altName: e.target.value })}
              placeholder="English alias (e.g. biscuit)"
              className="col-span-2 rounded-lg border border-stone-200 bg-white px-3 py-2 text-xs focus:border-emerald-500 focus:outline-none"
            />
            <select
              value={form.unit}
              onChange={(e) => setForm({ ...form, unit: e.target.value as Unit })}
              className="rounded-lg border border-stone-200 bg-white px-2 py-2 text-sm focus:outline-none"
            >
              {Object.entries(UNIT_LABELS).map(([u, l]) => (
                <option key={u} value={u}>
                  {l.hi}
                </option>
              ))}
            </select>
            <input
              type="number"
              min={0}
              value={form.stock}
              onChange={(e) => setForm({ ...form, stock: e.target.value })}
              placeholder="Stock"
              className="rounded-lg border border-stone-200 bg-white px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none"
            />
            <input
              type="number"
              min={0}
              value={form.cost}
              onChange={(e) => setForm({ ...form, cost: e.target.value })}
              placeholder="खरीद ₹"
              className="rounded-lg border border-stone-200 bg-white px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none"
            />
            <input
              type="number"
              min={0}
              value={form.sell}
              onChange={(e) => setForm({ ...form, sell: e.target.value })}
              placeholder="बिक्री ₹ *"
              className="rounded-lg border border-stone-200 bg-white px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none"
            />
          </div>
          <button
            onClick={submit}
            className="mt-3 w-full rounded-lg bg-stone-900 py-2 text-sm font-bold text-white hover:bg-stone-800"
          >
            Save item
          </button>
        </div>
      )}

      <ul className="max-h-[420px] divide-y divide-stone-100 overflow-y-auto">
        {items.length === 0 && (
          <li className="px-5 py-10 text-center text-sm text-stone-500">
            Inventory खाली है — add your first item.
          </li>
        )}
        {items.map((it) => {
          const low = it.stock <= LOW_STOCK;
          const margin = it.sellPrice - it.costPrice;
          return (
            <li key={it.id} className="group px-5 py-3 hover:bg-stone-50/70">
              <div className="flex items-center gap-3">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <p className="truncate text-sm font-bold text-stone-900">{it.name}</p>
                    {low && (
                      <span className="inline-flex items-center gap-1 rounded-full bg-red-100 px-1.5 py-0.5 text-[10px] font-bold text-red-700">
                        <AlertIcon className="h-2.5 w-2.5" /> कम stock
                      </span>
                    )}
                  </div>
                  <p className="text-[11px] text-stone-400">
                    {it.altName || "—"} · {inr(it.costPrice)} → {inr(it.sellPrice)} ·{" "}
                    <span className={margin >= 0 ? "font-semibold text-emerald-700" : "font-semibold text-red-600"}>
                      {margin >= 0 ? "+" : ""}
                      {inr(margin)}/{unitLabel(it.unit)}
                    </span>
                  </p>
                </div>
                <div className="text-right">
                  <p className={`font-display text-base font-bold ${low ? "text-red-600" : "text-stone-900"}`}>
                    {formatQty(it.stock)}
                  </p>
                  <p className="text-[10px] font-semibold text-stone-400 uppercase">
                    {unitLabel(it.unit)}
                  </p>
                </div>
                <div className="flex flex-col gap-1 opacity-0 transition group-hover:opacity-100 focus-within:opacity-100">
                  <button
                    onClick={() => {
                      onRestock(it.id, 10);
                      toast(`${it.name} +10 ${unitLabel(it.unit)} restocked`, "ok");
                    }}
                    className="rounded-md bg-emerald-50 px-2 py-0.5 text-[10px] font-bold text-emerald-700 hover:bg-emerald-100"
                    aria-label={`Restock ${it.name}`}
                  >
                    +10
                  </button>
                  <button
                    onClick={() => {
                      onDelete(it.id);
                      toast(`"${it.name}" deleted from inventory`, "warn");
                    }}
                    className="rounded-md bg-stone-100 p-1 text-stone-400 hover:bg-red-50 hover:text-red-600"
                    aria-label={`Delete ${it.name}`}
                  >
                    <TrashIcon className="h-3 w-3" />
                  </button>
                </div>
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
