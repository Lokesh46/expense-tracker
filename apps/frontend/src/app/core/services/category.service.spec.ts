import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { CategoryService } from './category.service';
import { API_BASE_URL } from '../tokens/api-base-url.token';
import { Category } from '../models/category.models';

const groceries: Category = { id: 1, name: 'Groceries', color: '#22c55e' };
const transport: Category = { id: 2, name: 'Transport', color: '#0ea5e9' };

describe('CategoryService', () => {
  let service: CategoryService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: '' },
      ],
    });
    service = TestBed.inject(CategoryService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function load(categories: Category[] = [groceries, transport]): void {
    service.load().subscribe();
    http.expectOne('/api/categories').flush(categories);
  }

  it('starts empty and fills from the server', () => {
    expect(service.categories()).toEqual([]);
    load();
    expect(service.categories()).toEqual([groceries, transport]);
  });

  it('adds a created category to the cache without reloading', () => {
    load();

    const created: Category = { id: 3, name: 'Books', color: '#a855f7' };
    service.create({ name: 'Books', color: '#a855f7' }).subscribe();
    http.expectOne({ url: '/api/categories', method: 'POST' }).flush(created);

    // Every screen reads this cache, so a stale one shows a category that
    // cannot be selected, or hides one that can.
    expect(service.categories()).toContain(created);
  });

  it('keeps the cache alphabetical after a create', () => {
    load();
    service.create({ name: 'Books' }).subscribe();
    http.expectOne({ url: '/api/categories', method: 'POST' })
      .flush({ id: 3, name: 'Books', color: '#a855f7' });

    expect(service.categories().map((c) => c.name)).toEqual(['Books', 'Groceries', 'Transport']);
  });

  it('replaces the edited category in the cache', () => {
    load();

    const renamed: Category = { id: 1, name: 'Food', color: '#22c55e' };
    service.update(1, { name: 'Food' }).subscribe();
    http.expectOne({ url: '/api/categories/1', method: 'PUT' }).flush(renamed);

    expect(service.categories().find((c) => c.id === 1)?.name).toBe('Food');
    expect(service.categories().length).toBe(2);
  });

  it('removes a deleted category from the cache', () => {
    load();

    service.delete(2).subscribe();
    http.expectOne({ url: '/api/categories/2', method: 'DELETE' }).flush(null);

    expect(service.categories().map((c) => c.id)).toEqual([1]);
  });

  it('leaves the cache alone when a delete fails', () => {
    load();

    // The backend refuses to delete a category still used by transactions;
    // removing it locally anyway would show a category that still exists.
    service.delete(1).subscribe({ error: () => undefined });
    http.expectOne({ url: '/api/categories/1', method: 'DELETE' })
      .flush({ message: 'in use' }, { status: 409, statusText: 'Conflict' });

    expect(service.categories().length).toBe(2);
  });

  it('exposes a colour lookup keyed by id', () => {
    load();

    expect(service.colorById().get(1)).toBe('#22c55e');
    expect(service.colorById().get(2)).toBe('#0ea5e9');
    expect(service.colorById().get(99)).toBeUndefined();
  });

  it('keeps the colour lookup in step with the cache', () => {
    load();

    service.update(1, { name: 'Groceries', color: '#ff0000' }).subscribe();
    http.expectOne({ url: '/api/categories/1', method: 'PUT' })
      .flush({ id: 1, name: 'Groceries', color: '#ff0000' });

    expect(service.colorById().get(1)).toBe('#ff0000');
  });
});
