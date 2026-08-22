import { Component, OnInit, AfterViewInit, inject, signal, computed, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TransactionService } from '../core/services/transaction.service';
import { CategoryService } from '../core/services/category.service';
import { Transaction } from '../core/models/transaction.models';
import { Category } from '../core/models/category.models';
import { Chart, ChartConfiguration, registerables } from 'chart.js';

Chart.register(...registerables);

interface MonthlyData {
  month: string;
  total: number;
}

interface DailyData {
  date: string;
  total: number;
}

interface CategoryData {
  name: string;
  total: number;
  percentage: number;
  color: string;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.css',
})
export class DashboardComponent implements OnInit, AfterViewInit {
  private readonly transactionService = inject(TransactionService);
  private readonly categoryService = inject(CategoryService);

  transactions = signal<Transaction[]>([]);
  categories = signal<Category[]>([]);

  selectedYear = signal(new Date().getFullYear());
  selectedMonth = signal(new Date().getMonth());
  viewMode = signal<'month' | 'year'>('month');
  
  // Date Range Picker signals
  dateRangeMode = signal<'preset' | 'custom'>('preset');
  customStartDate = signal<string>(this.getDefaultStartDate());
  customEndDate = signal<string>(this.getDefaultEndDate());
  
  // Available date range presets
  dateRangePresets = [
    { label: 'Last 7 Days', value: '7days' },
    { label: 'Last 30 Days', value: '30days' },
    { label: 'Last 90 Days', value: '90days' },
    { label: 'This Year', value: 'thisyear' },
    { label: 'Custom', value: 'custom' },
  ];

  monthlyData = computed(() => this.calculateMonthlyData());
  dailyData = computed(() => this.calculateDailyData());
  categoryData = computed(() => this.calculateCategoryData());
  categoryDataFiltered = computed(() => this.calculateCategoryDataFiltered());
  
  totalExpenses = computed(() => this.calculateFilteredTotal());
  
  averageExpense = computed(() => this.calculateFilteredAverage());

  topCategory = computed(() => {
    const catData = this.categoryDataFiltered();
    return catData.length > 0 ? catData[0] : null;
  });

  // Top transactions - show 5 highest expenses in the filtered period
  topTransactions = computed(() => {
    return this.filteredTransactionsComputed()
      .sort((a: Transaction, b: Transaction) => (b.amount || 0) - (a.amount || 0))
      .slice(0, 5);
  });

  @ViewChild('monthlyChart', { static: false }) monthlyChartRef?: ElementRef<HTMLCanvasElement>;
  @ViewChild('dailyChart', { static: false }) dailyChartRef?: ElementRef<HTMLCanvasElement>;
  @ViewChild('categoryChart', { static: false }) categoryChartRef?: ElementRef<HTMLCanvasElement>;

  monthlyChartInstance?: Chart;
  dailyChartInstance?: Chart;
  categoryChartInstance?: Chart;

  private readonly categoryColorCache = new Map<string, string>();

  constructor() {}

  // Computed filtered transactions for reactivity
  private readonly filteredTransactionsComputed = computed(() => {
    // Track primary filters
    this.selectedYear();
    this.selectedMonth();
    this.viewMode();
    return this.getFilteredTransactions();
  });

  private getDefaultStartDate(): string {
    const date = new Date();
    date.setDate(date.getDate() - 30);
    return date.toISOString().split('T')[0];
  }

  private getDefaultEndDate(): string {
    return new Date().toISOString().split('T')[0];
  }

  private getFilteredTransactions(): Transaction[] {
    // Filter by year/month only
    const year = this.selectedYear();
    const month = this.selectedMonth();
    const mode = this.viewMode();
    
    return this.transactions().filter((txn) => {
      const txnDate = new Date(txn.date);
      if (mode === 'year') {
        return txnDate.getFullYear() === year;
      } else {
        return txnDate.getFullYear() === year && txnDate.getMonth() === month;
      }
    });
  }

  getCategoryName(categoryId: string): string {
    return this.categories().find(c => c.id === categoryId)?.name || 'Unknown';
  }

  ngOnInit(): void {
    this.loadTransactions();
    this.loadCategories();
  }

  ngAfterViewInit(): void {
    setTimeout(() => {
      this.renderCharts();
    }, 300);
  }

  private loadTransactions(): void {
    this.transactionService.getTransactions().subscribe({
      next: (txns) => {
        this.transactions.set(txns);
        this.renderCharts();
      },
      error: (err) => console.error('Error loading transactions:', err),
    });
  }

  private loadCategories(): void {
    this.categoryService.getCategories().subscribe({
      next: (cats) => {
        this.categories.set(cats);
        this.renderCharts();
      },
      error: (err) => console.error('Error loading categories:', err),
    });
  }

  private calculateMonthlyData(): MonthlyData[] {
    // For monthly chart in YEAR view, use PRIMARY filter only (year)
    // Ignore the secondary date range filter for monthly chart
    const year = this.selectedYear();
    const monthlyMap = new Map<number, number>();

    // Initialize all months with 0
    for (let i = 0; i < 12; i++) {
      monthlyMap.set(i, 0);
    }

    // Sum transactions by month for the selected year (ignoring secondary date range filter)
    for (const txn of this.transactions()) {
      const date = new Date(txn.date);
      if (date.getFullYear() === year) {
        const month = date.getMonth();
        monthlyMap.set(month, (monthlyMap.get(month) || 0) + (txn.amount || 0));
      }
    }

    const monthNames = [
      'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
      'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'
    ];

    return Array.from(monthlyMap.entries()).map(([month, total]) => ({
      month: monthNames[month],
      total: Number.parseFloat(total.toFixed(2)),
    }));
  }

  private calculateDailyData(): DailyData[] {
    // For daily chart in MONTH view, use PRIMARY filter only (year/month)
    // Ignore the secondary date range filter for daily chart
    const year = this.selectedYear();
    const month = this.selectedMonth();
    const dailyMap = new Map<string, number>();

    // Get all transactions for the selected month (ignoring secondary date range filter)
    const monthTransactions = this.transactions().filter((txn) => {
      const txnDate = new Date(txn.date);
      return txnDate.getFullYear() === year && txnDate.getMonth() === month;
    });

    // Get days in the selected month
    const lastDay = new Date(year, month + 1, 0).getDate();
    for (let i = 1; i <= lastDay; i++) {
      const dateStr = `${year}-${String(month + 1).padStart(2, '0')}-${String(i).padStart(2, '0')}`;
      dailyMap.set(dateStr, 0);
    }

    // Sum transactions by day
    for (const txn of monthTransactions) {
      const dateStr = txn.date.substring(0, 10); // YYYY-MM-DD
      dailyMap.set(dateStr, (dailyMap.get(dateStr) || 0) + (txn.amount || 0));
    }

    return Array.from(dailyMap.entries()).map(([date, total]) => ({
      date: date.substring(5), // MM-DD format
      total: Number.parseFloat(total.toFixed(2)),
    }));
  }

  private calculateCategoryData(): CategoryData[] {
    const filtered = this.filteredTransactionsComputed();
    const categoryMap = new Map<string, number>();
    const categoryNameMap = new Map<string, string>();
    const categoryColorMap = new Map<string, string>();

    // Sum by category from filtered data
    for (const txn of filtered) {
      const catId = txn.categoryId || '0';
      categoryMap.set(catId, (categoryMap.get(catId) || 0) + (txn.amount || 0));
    }

    // Get category names and colors
    for (const cat of this.categories()) {
      categoryNameMap.set(cat.id, cat.name);
      categoryColorMap.set(cat.id, this.getCategoryColor(cat.name));
    }

    const total = Array.from(categoryMap.values()).reduce((sum, val) => sum + val, 0);

    return Array.from(categoryMap.entries())
      .map(([catId, amount]) => ({
        name: categoryNameMap.get(catId) || 'Unknown',
        total: Number.parseFloat(amount.toFixed(2)),
        percentage: total > 0 ? Number.parseFloat(((amount / total) * 100).toFixed(1)) : 0,
        color: categoryColorMap.get(catId) || '#8b5cf6',
      }))
      .sort((a, b) => b.total - a.total);
  }

  private calculateCategoryDataFiltered(): CategoryData[] {
    const categoryMap = new Map<string, { name: string; total: number }>();
    const filteredTxns = this.filteredTransactionsComputed();

    // Sum by category for filtered transactions and collect names
    for (const txn of filteredTxns) {
      const catId = txn.categoryId || '0';
      const current = categoryMap.get(catId);
      const catName = this.categories().find(c => c.id === catId)?.name || 'Unknown';
      
      categoryMap.set(catId, {
        name: catName,
        total: (current?.total || 0) + (txn.amount || 0),
      });
    }

    const total = Array.from(categoryMap.values()).reduce((sum, val) => sum + val.total, 0);

    return Array.from(categoryMap.entries())
      .map(([catId, data]) => ({
        name: data.name,
        total: Number.parseFloat(data.total.toFixed(2)),
        percentage: total > 0 ? Number.parseFloat(((data.total / total) * 100).toFixed(1)) : 0,
        color: this.getCategoryColor(data.name, catId),
      }))
      .sort((a, b) => b.total - a.total);
  }

  private getCategoryColor(categoryName: string, categoryId?: string): string {
    // Use categoryId for consistent color assignment
    const key = categoryId || categoryName.toLowerCase();

    // Check cache first
    if (this.categoryColorCache.has(key)) {
      return this.categoryColorCache.get(key)!;
    }

    // Predefined colors for common categories
    const categoryColors: { [key: string]: string } = {
      'food': '#FF6B6B',
      'transport': '#4ECDC4',
      'entertainment': '#FFE66D',
      'shopping': '#95E1D3',
      'utilities': '#C7CEEA',
      'health': '#FF8B94',
      'education': '#B4A7D6',
      'groceries': '#73C6B6',
      'dining': '#F7DC6F',
      'fuel': '#73B7F1',
      'rent': '#A9CCE3',
      'insurance': '#D7BDE2',
      'medical': '#F8B88B',
      'books': '#ABEBC6',
      'movies': '#F9E79F',
      'games': '#AED6F1',
      'gym': '#D5F4E6',
      'other': '#6b7280',
    };

    // Check if category name matches predefined colors
    const lowerName = categoryName.toLowerCase();
    if (categoryColors[lowerName]) {
      const color = categoryColors[lowerName];
      this.categoryColorCache.set(key, color);
      return color;
    }

    // Generate unique color based on categoryId hash
    const color = this.generateColorFromHash(key);
    this.categoryColorCache.set(key, color);
    return color;
  }

  private generateColorFromHash(key: string): string {
    // Just use random colors for better variety
    return this.getRandomColor();
  }

  private getRandomColor(): string {
    const vibrantColors = [
      '#FF6B6B', '#4ECDC4', '#FFE66D', '#95E1D3',
      '#C7CEEA', '#FF8B94', '#B4A7D6', '#73C6B6',
      '#F7DC6F', '#73B7F1', '#A9CCE3', '#D7BDE2',
      '#F8B88B', '#ABEBC6', '#F9E79F', '#AED6F1',
      '#D5F4E6', '#FAD2E1', '#FFF59D', '#81C784',
      '#64B5F6', '#9575CD', '#FF8A80', '#80DEEA',
    ];
    return vibrantColors[Math.floor(Math.random() * vibrantColors.length)];
  }

  renderCharts(): void {
    setTimeout(() => {
      const mode = this.viewMode();
      if (mode === 'year') {
        this.renderMonthlyChart();
      } else {
        this.renderDailyChart();
      }
      this.renderCategoryChart();
    }, 100);
  }

  private renderMonthlyChart(): void {
    if (!this.monthlyChartRef) return;

    if (this.monthlyChartInstance) {
      this.monthlyChartInstance.destroy();
    }

    const data = this.monthlyData();
    const ctx = this.monthlyChartRef.nativeElement.getContext('2d');
    if (!ctx) return;

    // Create gradient colors for each bar
    const barColors = [
      '#FF6B6B', '#4ECDC4', '#FFE66D', '#95E1D3',
      '#C7CEEA', '#FF8B94', '#B4A7D6', '#73C6B6',
      '#F7DC6F', '#73B7F1', '#A9CCE3', '#D7BDE2',
    ];

    const config: ChartConfiguration = {
      type: 'bar',
      data: {
        labels: data.map((d) => d.month),
        datasets: [
          {
            label: 'Monthly Expenses',
            data: data.map((d) => d.total),
            backgroundColor: barColors,
            borderColor: barColors.map((color) => color),
            borderWidth: 2,
            borderRadius: 6,
            hoverBackgroundColor: barColors.map((color) => this.lightenColor(color, 20)),
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: true,
        plugins: {
          legend: { display: true, labels: { font: { size: 12, weight: 'bold' } } },
        },
        scales: {
          y: {
            beginAtZero: true,
            ticks: { callback: (value) => '₹' + value },
          },
        },
      },
    };

    this.monthlyChartInstance = new Chart(ctx, config);
  }

  private renderDailyChart(): void {
    if (!this.dailyChartRef) return;

    if (this.dailyChartInstance) {
      this.dailyChartInstance.destroy();
    }

    const data = this.dailyData();
    const ctx = this.dailyChartRef.nativeElement.getContext('2d');
    if (!ctx) return;

    const config: ChartConfiguration = {
      type: 'line',
      data: {
        labels: data.map((d) => d.date),
        datasets: [
          {
            label: 'Daily Expenses',
            data: data.map((d) => d.total),
            borderColor: '#FF6B6B',
            backgroundColor: 'rgba(255, 107, 107, 0.1)',
            borderWidth: 3,
            fill: true,
            tension: 0.4,
            pointBackgroundColor: '#FF6B6B',
            pointBorderColor: '#fff',
            pointBorderWidth: 2,
            pointRadius: 5,
            pointHoverRadius: 7,
            hoverBackgroundColor: '#FFB3B3',
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: true,
        plugins: {
          legend: { display: true, labels: { font: { size: 12, weight: 'bold' } } },
        },
        scales: {
          y: {
            beginAtZero: true,
            ticks: { callback: (value) => '₹' + value },
          },
        },
      },
    };

    this.dailyChartInstance = new Chart(ctx, config);
  }

  private renderCategoryChart(): void {
    if (!this.categoryChartRef) return;

    if (this.categoryChartInstance) {
      this.categoryChartInstance.destroy();
    }

    const data = this.categoryDataFiltered();
    const ctx = this.categoryChartRef.nativeElement.getContext('2d');
    if (!ctx) return;

    const config: ChartConfiguration = {
      type: 'doughnut',
      data: {
        labels: data.map((d) => d.name),
        datasets: [
          {
            data: data.map((d) => d.total),
            backgroundColor: data.map((d) => d.color),
            borderColor: '#fff',
            borderWidth: 2,
            hoverBorderColor: '#000',
            hoverBorderWidth: 3,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: true,
        plugins: {
          legend: { 
            position: 'bottom', 
            labels: { font: { size: 12, weight: 'bold' }, padding: 20 } 
          },
          tooltip: {
            callbacks: {
              label: (context) => {
                const catData = data[context.dataIndex];
                return `${catData.name}: ₹${catData.total} (${catData.percentage}%)`;
              },
            },
            padding: 12,
            backgroundColor: 'rgba(0, 0, 0, 0.8)',
            titleFont: { size: 13, weight: 'bold' },
            bodyFont: { size: 12 },
          },
        },
      },
    };

    this.categoryChartInstance = new Chart(ctx, config);
  }

  private calculateFilteredTotal(): number {
    return this.filteredTransactionsComputed()
      .reduce((sum, t) => sum + (t.amount || 0), 0);
  }

  private calculateFilteredAverage(): number {
    const filtered = this.filteredTransactionsComputed();
    return filtered.length > 0 ? this.calculateFilteredTotal() / filtered.length : 0;
  }

  private lightenColor(color: string, percent: number): string {
    const num = Number.parseInt(color.replace('#', ''), 16);
    const amt = Math.round(2.55 * percent);
    const R = Math.min(255, (num >> 16) + amt);
    const G = Math.min(255, ((num >> 8) & 0x00ff) + amt);
    const B = Math.min(255, (num & 0x0000ff) + amt);
    return '#' + (0x1000000 + R * 0x10000 + G * 0x100 + B).toString(16).slice(1);
  }

  onYearChange(year: any): void {
    const numYear = typeof year === 'string' ? Number.parseInt(year, 10) : year;
    console.log('Year changed to:', numYear);
    this.selectedYear.set(numYear);
    this.renderCharts();
  }

  onMonthChange(month: any): void {
    const numMonth = typeof month === 'string' ? Number.parseInt(month, 10) : month;
    console.log('Month changed to:', numMonth);
    this.selectedMonth.set(numMonth);
    this.renderCharts();
  }

  getMonthName(monthIndex: number): string {
    const monthNames = [
      'January', 'February', 'March', 'April', 'May', 'June',
      'July', 'August', 'September', 'October', 'November', 'December'
    ];
    return monthNames[monthIndex];
  }

  getAvailableYears(): number[] {
    const currentYear = new Date().getFullYear();
    const years = [];
    for (let i = currentYear; i >= currentYear - 5; i--) {
      years.push(i);
    }
    return years;
  }

  onViewModeChange(mode: 'month' | 'year'): void {
    this.viewMode.set(mode);
    this.renderCharts();
  }

  onDateRangePresetChange(preset: string): void {
    const today = new Date();
    let startDate = new Date();

    switch (preset) {
      case '7days':
        startDate.setDate(today.getDate() - 7);
        break;
      case '30days':
        startDate.setDate(today.getDate() - 30);
        break;
      case '90days':
        startDate.setDate(today.getDate() - 90);
        break;
      case 'thisyear':
        startDate = new Date(today.getFullYear(), 0, 1);
        break;
      case 'custom':
        this.dateRangeMode.set('custom');
        return;
      default:
        return;
    }

    this.dateRangeMode.set('preset');
    this.customStartDate.set(startDate.toISOString().split('T')[0]);
    this.customEndDate.set(today.toISOString().split('T')[0]);
    this.renderCharts();
  }

  onCustomDateChange(): void {
    this.renderCharts();
  }
}
