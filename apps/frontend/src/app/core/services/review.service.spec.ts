import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { ReviewService } from './review.service';
import { API_BASE_URL } from '../tokens/api-base-url.token';
import { MerchantGroup, ReviewQueue } from '../models/review.models';

const swiggy: MerchantGroup = {
  merchantHash: 'ogsbtDqf_p8C',
  merchantName: 'swiggy',
  transactionCount: 3,
  totals: [{ currency: 'INR', amount: 1356.5 }],
  suggestedCategoryId: 7,
  suggestedCategoryName: 'Eating Out',
  source: 'HISTORY',
  samples: ['UPI-SWIGGY-1234@ybl', 'POS 4123XXXX9876 SWIGGY BANGALORE'],
};

const queue: ReviewQueue = { merchants: [swiggy], merchantsTotal: 1, transactions: 3 };

describe('ReviewService', () => {
  let service: ReviewService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: '' },
      ],
    });
    service = TestBed.inject(ReviewService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('starts at nothing waiting', () => {
    expect(service.waiting()).toBe(0);
  });

  it('publishes the waiting count when the queue is loaded', () => {
    // The badge lives in the navigation and the queue lives on its own screen.
    // One count, updated wherever it is learnt, is what keeps them agreeing.
    service.merchants().subscribe();
    http.expectOne('/api/review/merchants').flush(queue);

    expect(service.waiting()).toBe(3);
  });

  it('publishes the waiting count from the summary alone', () => {
    service.refreshCount().subscribe();
    http
      .expectOne('/api/review/summary')
      .flush({ merchants: [], merchantsTotal: 2, transactions: 9 });

    expect(service.waiting()).toBe(9);
  });

  it('approves a merchant by its digest', () => {
    service.approve('ogsbtDqf_p8C').subscribe();

    const request = http.expectOne('/api/review/merchants/ogsbtDqf_p8C/approve');
    expect(request.request.method).toBe('POST');
    request.flush({ updated: 3, ruleCreated: false, message: '3 transactions approved.' });
  });

  it('sends the category and whether to remember it', () => {
    service.assign('ogsbtDqf_p8C', { categoryId: 7, createRule: true }).subscribe();

    const request = http.expectOne('/api/review/merchants/ogsbtDqf_p8C/assign');
    expect(request.request.body).toEqual({ categoryId: 7, createRule: true });
    request.flush({ updated: 3, ruleCreated: true, message: 'Moved.' });
  });

  it('can refile without remembering, for a one-off', () => {
    service.assign('ogsbtDqf_p8C', { categoryId: 7, createRule: false }).subscribe();

    const request = http.expectOne('/api/review/merchants/ogsbtDqf_p8C/assign');
    expect(request.request.body.createRule).toBeFalse();
    request.flush({ updated: 3, ruleCreated: false, message: 'Moved.' });
  });

  it('approves everything at once', () => {
    service.approveAll().subscribe();

    const request = http.expectOne('/api/review/approve-all');
    expect(request.request.method).toBe('POST');
    request.flush({ updated: 8, ruleCreated: false, message: '8 transactions approved.' });
  });
});
