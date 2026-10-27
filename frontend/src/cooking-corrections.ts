export function validCookedWeight(value: string): boolean {
  return /^\d{1,6}(\.\d{1,2})?$/.test(value) && Number(value) > 0;
}
export type CookingChange = {
  id: string;
  batchId: string;
  entryId: string | null;
  kind: 'YIELD' | 'CONSUMPTION_REVERSAL';
  reason: string;
  createdAt: string;
  before: Record<string, unknown>;
  after: Record<string, unknown>;
};
