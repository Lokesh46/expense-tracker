import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpRequest, provideHttpClient } from '@angular/common/http';

import { TransactionService } from './transaction.service';
import { API_BASE_URL } from '../tokens/api-base-url.token';

describe('TransactionService', () => {
  let service: TransactionService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: '' },
      ],
    });
    service = TestBed.inject(TransactionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** Captures the single outstanding request to /api/transactions. */
  function capture(): HttpRequest<unknown> {
    const match = http.expectOne((req) => req.url === '/api/transactions');
    match.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true });
    return match.request;
  }

  it('sends paging and sorting in the shape Spring Data expects', () => {
    service.search({ page: 2, size: 50, sortBy: 'amount', sortDir: 'asc' }).subscribe();

    const params = capture().params;
    expect(params.get('page')).toBe('2');
    expect(params.get('size')).toBe('50');
    // Spring reads a single "sort=field,direction" parameter.
    expect(params.get('sort')).toBe('amount,asc');
  });

  it('defaults the sort direction to descending', () => {
    service.search({ sortBy: 'date' }).subscribe();
    expect(capture().params.get('sort')).toBe('date,desc');
  });

  it('omits filters that are not set', () => {
    service
      .search({ page: 0, categoryId: null, from: null, search: '', paymentMethod: undefined })
      .subscribe();

    const params = capture().params;
    // Sending "categoryId=" would be read as a filter on the empty value rather
    // than as no filter at all.
    expect(params.has('categoryId')).toBe(false);
    expect(params.has('from')).toBe(false);
    expect(params.has('search')).toBe(false);
    expect(params.has('paymentMethod')).toBe(false);
  });

  it('keeps a zero amount filter, which is a real value', () => {
    service.search({ minAmount: 0 }).subscribe();
    expect(capture().params.get('minAmount')).toBe('0');
  });

  it('trims whitespace from the search term', () => {
    service.search({ search: '  coffee  ' }).subscribe();
    expect(capture().params.get('search')).toBe('coffee');
  });

  it('drops a search term that is only whitespace', () => {
    service.search({ search: '   ' }).subscribe();
    expect(capture().params.has('search')).toBe(false);
  });

  it('passes every filter through', () => {
    service
      .search({
        categoryId: 3,
        from: '2026-08-01',
        to: '2026-08-31',
        minAmount: 5,
        maxAmount: 500,
        paymentMethod: 'Cash',
        search: 'shop',
      })
      .subscribe();

    const params = capture().params;
    expect(params.get('categoryId')).toBe('3');
    expect(params.get('from')).toBe('2026-08-01');
    expect(params.get('to')).toBe('2026-08-31');
    expect(params.get('minAmount')).toBe('5');
    expect(params.get('maxAmount')).toBe('500');
    expect(params.get('paymentMethod')).toBe('Cash');
    expect(params.get('search')).toBe('shop');
  });

  it('exports with the current filters but without paging', () => {
    service.exportCsv({ page: 3, size: 20, categoryId: 7 }).subscribe();

    const match = http.expectOne((req) => req.url === '/api/transactions/export');
    match.flush(new Blob(['Date,Description'], { type: 'text/csv' }));

    // Exporting only the page being viewed would silently truncate the file.
    expect(match.request.params.has('page')).toBe(false);
    expect(match.request.params.has('size')).toBe(false);
    expect(match.request.params.get('categoryId')).toBe('7');
    expect(match.request.responseType).toBe('blob');
  });

  it('uploads an import as multipart form data, with the chosen date order', () => {
    const file = new File(['Date,Description'], 'statement.csv', { type: 'text/csv' });
    service.importCsv(file, 'MONTH_FIRST', 'GBP').subscribe();

    const match = http.expectOne('/api/transactions/import');
    match.flush({ imported: 1, skipped: 0, flagged: 0, errors: [], columnMapping: '' });

    expect(match.request.body instanceof FormData).toBe(true);
    expect((match.request.body as FormData).get('file')).toBe(file);
    // Sent with the file rather than remembered on the server: it describes
    // this file, not the account.
    expect((match.request.body as FormData).get('dateOrder')).toBe('MONTH_FIRST');
    expect((match.request.body as FormData).get('defaultCurrency')).toBe('GBP');
  });

  it('targets the right URL for each operation', () => {
    service.getById(4).subscribe();
    http.expectOne({ url: '/api/transactions/4', method: 'GET' }).flush({});

    service.update(4, {} as never).subscribe();
    http.expectOne({ url: '/api/transactions/4', method: 'PUT' }).flush({});

    service.delete(4).subscribe();
    http.expectOne({ url: '/api/transactions/4', method: 'DELETE' }).flush(null);
  });
});
