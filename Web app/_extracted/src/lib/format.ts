import type { Unit } from "../types";

export const UNIT_LABELS: Record<Unit, { hi: string; en: string }> = {
  kg: { hi: "किलो", en: "kg" },
  g: { hi: "ग्राम", en: "g" },
  L: { hi: "लीटर", en: "L" },
  ml: { hi: "मिली", en: "ml" },
  pc: { hi: "पीस", en: "pc" },
  pkt: { hi: "पैकेट", en: "pkt" },
  btl: { hi: "बोतल", en: "btl" },
  dozen: { hi: "दर्जन", en: "dozen" },
  bag: { hi: "कट्टा", en: "bag" },
};

export function unitLabel(u: Unit): string {
  return UNIT_LABELS[u].hi;
}

export function inr(n: number): string {
  return "₹" + n.toLocaleString("en-IN", { maximumFractionDigits: 2 });
}

export function formatQty(n: number): string {
  return Number.isInteger(n) ? n.toString() : n.toString();
}

export function formatTime(epoch: number): string {
  return new Date(epoch).toLocaleTimeString("en-IN", {
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function isToday(epoch: number): boolean {
  const d = new Date(epoch);
  const now = new Date();
  return (
    d.getDate() === now.getDate() &&
    d.getMonth() === now.getMonth() &&
    d.getFullYear() === now.getFullYear()
  );
}

export function todayString(): string {
  return new Date().toLocaleDateString("en-IN", {
    weekday: "long",
    day: "numeric",
    month: "long",
  });
}
