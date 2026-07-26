import type { Item } from "../types";

/** Typical kirana store items with realistic prices */
export const SEED_ITEMS: Item[] = [
  { id: "i1", name: "चीनी", altName: "sugar", unit: "kg", stock: 25, costPrice: 40, sellPrice: 44 },
  { id: "i2", name: "आटा", altName: "atta flour", unit: "kg", stock: 50, costPrice: 32, sellPrice: 36 },
  { id: "i3", name: "चावल", altName: "rice", unit: "kg", stock: 40, costPrice: 55, sellPrice: 62 },
  { id: "i4", name: "दूध", altName: "milk", unit: "pkt", stock: 30, costPrice: 21, sellPrice: 24 },
  { id: "i5", name: "सरसों तेल", altName: "oil mustard", unit: "btl", stock: 15, costPrice: 120, sellPrice: 135 },
  { id: "i6", name: "चाय पत्ती", altName: "tea", unit: "pkt", stock: 18, costPrice: 35, sellPrice: 40 },
  { id: "i7", name: "नमक", altName: "salt", unit: "pkt", stock: 40, costPrice: 10, sellPrice: 12 },
  { id: "i8", name: "साबुन", altName: "soap", unit: "pc", stock: 24, costPrice: 18, sellPrice: 22 },
  { id: "i9", name: "दाल", altName: "dal lentil", unit: "kg", stock: 30, costPrice: 85, sellPrice: 95 },
  { id: "i10", name: "अंडे", altName: "eggs", unit: "dozen", stock: 8, costPrice: 60, sellPrice: 72 },
];
