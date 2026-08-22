/**
 * Money formatting.
 *
 * Each transaction carries its own currency, so amounts are formatted with the
 * currency they were recorded in rather than a single hardcoded symbol.
 */

const formatters = new Map<string, Intl.NumberFormat>();

function formatterFor(currency: string): Intl.NumberFormat {
  const key = currency.toUpperCase();
  let formatter = formatters.get(key);

  if (!formatter) {
    try {
      formatter = new Intl.NumberFormat(undefined, {
        style: 'currency',
        currency: key,
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      });
    } catch {
      // An unknown code would otherwise throw; fall back to a plain number.
      formatter = new Intl.NumberFormat(undefined, {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      });
    }
    formatters.set(key, formatter);
  }

  return formatter;
}

export function formatMoney(amount: number, currency: string): string {
  return formatterFor(currency).format(amount ?? 0);
}

/** Groups amounts by currency. */
export function totalsByCurrency(
  items: { amount: number; currency: string }[]
): { currency: string; total: number }[] {
  const totals = new Map<string, number>();

  for (const item of items) {
    const key = (item.currency ?? '').toUpperCase() || 'UNKNOWN';
    totals.set(key, (totals.get(key) ?? 0) + (item.amount ?? 0));
  }

  return [...totals.entries()]
    .map(([currency, total]) => ({ currency, total }))
    .sort((a, b) => b.total - a.total);
}

/**
 * The currency a user works in most, inferred from their transactions.
 *
 * Used to label figures that are genuinely a single number — a chart axis, a
 * budget limit — where showing several currencies at once would not fit.
 */
export function dominantCurrency(
  items: { amount: number; currency: string }[],
  fallback = 'GBP'
): string {
  return totalsByCurrency(items)[0]?.currency ?? fallback;
}
