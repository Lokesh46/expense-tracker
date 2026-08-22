import { dominantCurrency, formatMoney, totalsByCurrency } from './money';

describe('money', () => {
  describe('formatMoney', () => {
    it('formats using the currency the amount was recorded in', () => {
      // The old dashboard hardcoded a rupee symbol regardless of the currency
      // stored on the transaction.
      expect(formatMoney(1234.5, 'GBP')).toContain('£');
      expect(formatMoney(1234.5, 'USD')).toContain('$');
      expect(formatMoney(1234.5, 'INR')).toContain('₹');
    });

    it('always shows two decimal places', () => {
      expect(formatMoney(5, 'GBP')).toContain('5.00');
      expect(formatMoney(5.1, 'GBP')).toContain('5.10');
    });

    it('is case insensitive about the currency code', () => {
      expect(formatMoney(10, 'gbp')).toEqual(formatMoney(10, 'GBP'));
    });

    it('falls back to a plain number for an unknown code rather than throwing', () => {
      expect(() => formatMoney(10, 'NOTACURRENCY')).not.toThrow();
      expect(formatMoney(10, 'NOTACURRENCY')).toContain('10.00');
    });

    it('treats a missing amount as zero', () => {
      expect(formatMoney(undefined as unknown as number, 'GBP')).toContain('0.00');
    });
  });

  describe('totalsByCurrency', () => {
    it('groups amounts by currency instead of summing across them', () => {
      const totals = totalsByCurrency([
        { amount: 10, currency: 'GBP' },
        { amount: 5, currency: 'USD' },
        { amount: 20, currency: 'GBP' },
      ]);

      expect(totals).toEqual([
        { currency: 'GBP', total: 30 },
        { currency: 'USD', total: 5 },
      ]);
    });

    it('normalises the currency code so casing does not split a group', () => {
      const totals = totalsByCurrency([
        { amount: 10, currency: 'gbp' },
        { amount: 10, currency: 'GBP' },
      ]);

      expect(totals).toEqual([{ currency: 'GBP', total: 20 }]);
    });

    it('orders by size, largest first', () => {
      const totals = totalsByCurrency([
        { amount: 1, currency: 'USD' },
        { amount: 100, currency: 'EUR' },
      ]);

      expect(totals[0].currency).toBe('EUR');
    });

    it('returns nothing for an empty ledger', () => {
      expect(totalsByCurrency([])).toEqual([]);
    });
  });

  describe('dominantCurrency', () => {
    it('picks the currency with the largest total', () => {
      expect(
        dominantCurrency([
          { amount: 5, currency: 'USD' },
          { amount: 500, currency: 'INR' },
        ])
      ).toBe('INR');
    });

    it('uses the fallback when there is nothing to go on', () => {
      expect(dominantCurrency([], 'EUR')).toBe('EUR');
    });
  });
});
