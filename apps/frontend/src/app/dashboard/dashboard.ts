import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Chart, ChartConfiguration, registerables } from 'chart.js';

import { BudgetService } from '../core/services/budget.service';
import { CategoryService } from '../core/services/category.service';
import { NotificationService } from '../core/services/notification.service';
import { TransactionService } from '../core/services/transaction.service';
import { Budget } from '../core/models/budget.models';
import { Transaction } from '../core/models/transaction.models';
import { ThemeService } from '../core/services/theme.service';
import { describeError } from '../core/utils/api-error';
import {
  dominantCurrency,
  expensesOnly,
  formatMoney,
  incomeOnly,
  totalsByCurrency,
} from '../core/utils/money';

Chart.register(...registerables);

interface CategorySlice {
  name: string;
  total: number;
  color: string;
  share: number;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.css',
})
export class DashboardComponent implements AfterViewInit, OnDestroy {
  private readonly transactionService = inject(TransactionService);
  private readonly categoryService = inject(CategoryService);
  private readonly budgetService = inject(BudgetService);
  private readonly notifications = inject(NotificationService);
  private readonly themeService = inject(ThemeService);

  private readonly trendCanvas = viewChild<ElementRef<HTMLCanvasElement>>('trendChart');
  private readonly splitCanvas = viewChild<ElementRef<HTMLCanvasElement>>('splitChart');

  private trend?: Chart;
  private split?: Chart;
  private viewReady = false;

  protected readonly month = signal(new Date().toISOString().slice(0, 7));
  protected readonly current = signal<Transaction[]>([]);
  protected readonly previous = signal<Transaction[]>([]);
  protected readonly budgets = signal<Budget[]>([]);
  protected readonly isLoading = signal(true);

  /**
   * Spending is expenses only.
   *
   * Amounts are stored positive whichever way the money went, so summing the
   * month wholesale counts a refund as though it were a purchase. Every figure
   * below that means "spent" reads from here rather than from `current`.
   */
  protected readonly currentExpenses = computed(() => expensesOnly(this.current()));
  protected readonly previousExpenses = computed(() => expensesOnly(this.previous()));
  protected readonly currentIncome = computed(() => incomeOnly(this.current()));

  protected readonly income = computed(() =>
    this.currentIncome().reduce((sum, t) => sum + t.amount, 0)
  );

  protected readonly hasIncome = computed(() => this.currentIncome().length > 0);

  protected readonly currency = computed(() => dominantCurrency(this.current()));

  protected readonly monthLabel = computed(() =>
    new Date(`${this.month()}-01T00:00:00`).toLocaleDateString(undefined, {
      month: 'long',
      year: 'numeric',
    })
  );

  protected readonly total = computed(() =>
    this.currentExpenses().reduce((sum, t) => sum + t.amount, 0)
  );

  protected readonly previousTotal = computed(() =>
    this.previousExpenses().reduce((sum, t) => sum + t.amount, 0)
  );

  /** Percentage change against the previous month, or null when there is no basis. */
  protected readonly change = computed(() => {
    const before = this.previousTotal();
    if (before === 0) {
      return null;
    }
    return ((this.total() - before) / before) * 100;
  });

  /**
   * More than one currency in a month means the headline total is the sum of
   * unlike things. The UI says so rather than quietly presenting a wrong number.
   */
  protected readonly mixedCurrencies = computed(
    () => totalsByCurrency(this.currentExpenses()).length > 1
  );

  protected readonly currencyBreakdown = computed(() =>
    totalsByCurrency(this.currentExpenses())
  );

  protected readonly categorySlices = computed<CategorySlice[]>(() => {
    const colors = this.categoryService.colorById();
    const totals = new Map<number, { name: string; total: number }>();

    for (const t of this.currentExpenses()) {
      const entry = totals.get(t.categoryId) ?? { name: t.categoryName, total: 0 };
      entry.total += t.amount;
      totals.set(t.categoryId, entry);
    }

    const grand = this.total();

    return [...totals.entries()]
      .map(([id, entry]) => ({
        name: entry.name,
        total: entry.total,
        color: colors.get(id) ?? '#e0a959',
        share: grand > 0 ? (entry.total / grand) * 100 : 0,
      }))
      .sort((a, b) => b.total - a.total);
  });

  protected readonly topCategory = computed(() => this.categorySlices()[0] ?? null);

  protected readonly recent = computed(() =>
    [...this.current()]
      .sort((a, b) => b.date.localeCompare(a.date))
      .slice(0, 6)
  );

  protected readonly overBudget = computed(() => this.budgets().filter((b) => b.exceeded));

  /** Daily totals across the month, used for the trend chart. */
  private readonly dailyTotals = computed(() => {
    const [year, month] = this.month().split('-').map(Number);
    const days = new Date(year, month, 0).getDate();
    const totals = new Array<number>(days).fill(0);

    for (const t of this.currentExpenses()) {
      const day = Number(t.date.slice(8, 10));
      if (day >= 1 && day <= days) {
        totals[day - 1] += t.amount;
      }
    }
    return totals;
  });

  constructor() {
    this.categoryService.load().subscribe();

    effect(() => {
      this.month();
      this.loadMonth();
    });

    // Charts read their colours from CSS variables, which change with the
    // theme, so they are rebuilt when it is toggled.
    effect(() => {
      this.themeService.theme();
      if (this.viewReady) {
        this.renderCharts();
      }
    });

    effect(() => {
      this.categorySlices();
      this.dailyTotals();
      if (this.viewReady) {
        this.renderCharts();
      }
    });
  }

  ngAfterViewInit(): void {
    this.viewReady = true;
    this.renderCharts();
  }

  ngOnDestroy(): void {
    this.trend?.destroy();
    this.split?.destroy();
  }

  protected shiftMonth(delta: number): void {
    const [year, month] = this.month().split('-').map(Number);
    const date = new Date(year, month - 1 + delta, 1);
    this.month.set(`${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`);
  }

  // ------------------------------------------------------------------ data

  private loadMonth(): void {
    const month = this.month();
    const [year, monthNumber] = month.split('-').map(Number);

    const firstOfMonth = `${month}-01`;
    const lastOfMonth = `${month}-${String(new Date(year, monthNumber, 0).getDate()).padStart(2, '0')}`;

    const before = new Date(year, monthNumber - 2, 1);
    const previousMonth = `${before.getFullYear()}-${String(before.getMonth() + 1).padStart(2, '0')}`;
    const previousLast = new Date(before.getFullYear(), before.getMonth() + 1, 0).getDate();

    this.isLoading.set(true);

    // A generous page size: a personal account rarely exceeds this in one
    // month, and the dashboard needs the whole month to aggregate over.
    this.transactionService
      .search({ from: firstOfMonth, to: lastOfMonth, size: 1000, sortBy: 'date', sortDir: 'desc' })
      .subscribe({
        next: (page) => {
          this.current.set(page.content);
          this.isLoading.set(false);
        },
        error: (err) => {
          this.isLoading.set(false);
          this.notifications.showError(describeError(err, 'Could not load the overview.'));
        },
      });

    this.transactionService
      .search({
        from: `${previousMonth}-01`,
        to: `${previousMonth}-${String(previousLast).padStart(2, '0')}`,
        size: 1000,
      })
      .subscribe({ next: (page) => this.previous.set(page.content) });

    this.budgetService.load(month).subscribe({ next: (budgets) => this.budgets.set(budgets) });
  }

  // ---------------------------------------------------------------- charts

  private cssVar(name: string): string {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  private renderCharts(): void {
    // Defer a frame so the canvases have been laid out and the new theme's
    // variables have been applied to the document.
    requestAnimationFrame(() => {
      this.renderTrend();
      this.renderSplit();
    });
  }

  private renderTrend(): void {
    const canvas = this.trendCanvas()?.nativeElement;
    if (!canvas) {
      return;
    }

    const grid = this.cssVar('--rule');
    const label = this.cssVar('--text-tertiary');
    const accent = this.cssVar('--accent');
    const totals = this.dailyTotals();

    const config: ChartConfiguration<'bar'> = {
      type: 'bar',
      data: {
        labels: totals.map((_, index) => String(index + 1)),
        datasets: [
          {
            data: totals,
            backgroundColor: accent,
            hoverBackgroundColor: this.cssVar('--accent-strong'),
            borderRadius: 2,
            borderSkipped: false,
            // Bars stay slim so the month reads as a rhythm of days rather
            // than a solid block.
            barPercentage: 0.62,
            categoryPercentage: 0.9,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            displayColors: false,
            backgroundColor: this.cssVar('--surface-raised'),
            titleColor: this.cssVar('--text-primary'),
            bodyColor: this.cssVar('--text-secondary'),
            borderColor: this.cssVar('--rule-strong'),
            borderWidth: 1,
            padding: 10,
            callbacks: {
              title: (items) => `Day ${items[0].label}`,
              label: (item) => formatMoney(item.parsed.y ?? 0, this.currency()),
            },
          },
        },
        scales: {
          x: {
            grid: { display: false },
            border: { color: grid },
            ticks: {
              color: label,
              font: { family: this.cssVar('--font-mono'), size: 10 },
              maxRotation: 0,
              // Label roughly every fifth day; one per day is unreadable.
              callback: (_value, index) => (index % 5 === 0 ? String(index + 1) : ''),
            },
          },
          y: {
            beginAtZero: true,
            grid: { color: grid },
            border: { display: false },
            ticks: {
              color: label,
              font: { family: this.cssVar('--font-mono'), size: 10 },
              maxTicksLimit: 5,
            },
          },
        },
      },
    };

    this.trend?.destroy();
    this.trend = new Chart(canvas, config);
  }

  private renderSplit(): void {
    const canvas = this.splitCanvas()?.nativeElement;
    if (!canvas) {
      return;
    }

    const slices = this.categorySlices();

    const config: ChartConfiguration<'doughnut'> = {
      type: 'doughnut',
      data: {
        labels: slices.map((s) => s.name),
        datasets: [
          {
            data: slices.map((s) => s.total),
            backgroundColor: slices.map((s) => s.color),
            borderColor: this.cssVar('--surface'),
            borderWidth: 2,
            hoverOffset: 6,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        // A thin ring: the figure in the middle is the message, not the ring.
        cutout: '72%',
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: this.cssVar('--surface-raised'),
            titleColor: this.cssVar('--text-primary'),
            bodyColor: this.cssVar('--text-secondary'),
            borderColor: this.cssVar('--rule-strong'),
            borderWidth: 1,
            padding: 10,
            callbacks: {
              label: (item) => formatMoney(item.parsed, this.currency()),
            },
          },
        },
      },
    };

    this.split?.destroy();
    this.split = new Chart(canvas, config);
  }

  // --------------------------------------------------------------- display

  protected categoryColor(id: number): string {
    return this.categoryService.colorById().get(id) ?? 'var(--accent)';
  }

  protected money(amount: number): string {
    return formatMoney(amount, this.currency());
  }

  protected formatTotal(entry: { currency: string; total: number }): string {
    return formatMoney(entry.total, entry.currency);
  }

  protected changeLabel(): string {
    const change = this.change();
    if (change === null) {
      return 'No comparison';
    }
    const sign = change > 0 ? '+' : '';
    return `${sign}${change.toFixed(1)}% vs last month`;
  }
}
