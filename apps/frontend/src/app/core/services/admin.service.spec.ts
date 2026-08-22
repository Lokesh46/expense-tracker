import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AdminService } from './admin.service';
import { API_BASE_URL } from '../tokens/api-base-url.token';
import { UserSummary } from '../models/user.models';

const mia: UserSummary = {
  id: 7,
  username: 'mia',
  email: 'mia@example.com',
  role: 'MEMBER',
  status: 'ACTIVE',
  createdAt: '2026-08-01T09:00:00Z',
  lastLoginAt: '2026-08-22T09:14:00Z',
  loginCount: 38,
  lockedUntil: null,
};

const emptyPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true };

describe('AdminService', () => {
  let service: AdminService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE_URL, useValue: '' },
      ],
    });
    service = TestBed.inject(AdminService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  describe('searchUsers', () => {
    /**
     * An empty `search=` is a filter matching nothing, not the absence of one.
     * Sending blanks is how a list silently comes back empty.
     */
    it('omits blank and null filters entirely', () => {
      service.searchUsers({ page: 0, size: 20, search: '', role: null, status: null }).subscribe();

      const request = http.expectOne((req) => req.url === '/api/admin/users');
      expect(request.request.params.has('search')).toBe(false);
      expect(request.request.params.has('role')).toBe(false);
      expect(request.request.params.has('status')).toBe(false);
      expect(request.request.params.get('page')).toBe('0');
      expect(request.request.params.get('size')).toBe('20');

      request.flush(emptyPage);
    });

    it('sends the filters that do have values', () => {
      service.searchUsers({ search: 'mia', role: 'ADMIN', status: 'LOCKED' }).subscribe();

      const request = http.expectOne((req) => req.url === '/api/admin/users');
      expect(request.request.params.get('search')).toBe('mia');
      expect(request.request.params.get('role')).toBe('ADMIN');
      expect(request.request.params.get('status')).toBe('LOCKED');

      request.flush(emptyPage);
    });

    /** Spring reads one `sort` parameter as "property,direction". */
    it('combines sort field and direction into one parameter', () => {
      service.searchUsers({ sortBy: 'lastLoginAt', sortDir: 'asc' }).subscribe();

      const request = http.expectOne((req) => req.url === '/api/admin/users');
      expect(request.request.params.get('sort')).toBe('lastLoginAt,asc');

      request.flush(emptyPage);
    });

    it('defaults an unspecified direction to descending', () => {
      service.searchUsers({ sortBy: 'createdAt' }).subscribe();

      const request = http.expectOne((req) => req.url === '/api/admin/users');
      expect(request.request.params.get('sort')).toBe('createdAt,desc');

      request.flush(emptyPage);
    });
  });

  describe('updateUser', () => {
    /**
     * PATCH, not PUT. A PUT would send the whole object back, which makes two
     * administrators editing different fields overwrite each other and makes
     * clearing an email indistinguishable from not touching it.
     */
    it('patches only the fields given', () => {
      service.updateUser(7, { role: 'ADMIN' }).subscribe();

      const request = http.expectOne({ url: '/api/admin/users/7', method: 'PATCH' });
      expect(request.request.body).toEqual({ role: 'ADMIN' });
      expect(Object.keys(request.request.body as object)).not.toContain('email');

      request.flush({ ...mia, role: 'ADMIN' });
    });
  });

  describe('activity', () => {
    it('sends adverseOnly only when it is true', () => {
      service.searchActivity({ adverseOnly: false }).subscribe();
      let request = http.expectOne((req) => req.url === '/api/admin/activity');
      expect(request.request.params.has('adverseOnly')).toBe(false);
      request.flush(emptyPage);

      service.searchActivity({ adverseOnly: true }).subscribe();
      request = http.expectOne((req) => req.url === '/api/admin/activity');
      expect(request.request.params.get('adverseOnly')).toBe('true');
      request.flush(emptyPage);
    });

    /** Paging an export would return one page of a file, which is not an export. */
    it('drops paging from an export', () => {
      service.exportActivityCsv({ page: 3, size: 25, username: 'mia' }).subscribe();

      const request = http.expectOne((req) => req.url === '/api/admin/activity/export');
      expect(request.request.params.has('page')).toBe(false);
      expect(request.request.params.has('size')).toBe(false);
      expect(request.request.params.get('username')).toBe('mia');
      expect(request.request.responseType).toBe('blob');

      request.flush(new Blob(['When,Event\n']));
    });
  });

  describe('actions', () => {
    it('posts to the right action endpoints', () => {
      service.unlock(7).subscribe();
      http.expectOne({ url: '/api/admin/users/7/unlock', method: 'POST' }).flush(mia);

      service.revokeSessions(7).subscribe();
      http.expectOne({ url: '/api/admin/users/7/revoke-sessions', method: 'POST' }).flush(null);

      service.setPassword(7, 'BrandNewPass1!').subscribe();
      const password = http.expectOne({ url: '/api/admin/users/7/password', method: 'POST' });
      expect(password.request.body).toEqual({ newPassword: 'BrandNewPass1!' });
      password.flush(null);

      service.deleteUser(7).subscribe();
      http.expectOne({ url: '/api/admin/users/7', method: 'DELETE' }).flush(null);
    });
  });
});
