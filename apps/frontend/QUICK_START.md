# Quick Start Guide - Frontend Category & Transaction Integration

## 🚀 Quick Setup

### Prerequisites
- Node.js installed
- Backend running on `http://localhost:8081`
- Angular 20.3.0

### Installation
```bash
cd Frontend
npm install
```

### Start Development Server
```bash
npm start
```

Navigate to `http://localhost:4200`

## 📍 Navigation

From the home page or header, access:
- **Categories** → Manage expense categories
- **Transactions** → View and manage transactions

## 🎯 Common Tasks

### Create a Category
1. Click "Categories" in navigation
2. Click "Create New Category" button
3. Enter category name (3-50 characters)
4. Click "Create Category"
5. Success notification appears

### Create a Transaction
1. Click "Transactions" in navigation
2. Click "Add New Transaction" button
3. Fill in all required fields:
   - **Amount**: Enter transaction amount
   - **Currency**: Select currency
   - **Category**: Select from dropdown
   - **Date**: Select date
   - **Payment Method**: Select method
   - **Description**: Enter description
   - **Comments**: (Optional) Add comments
4. Click "Create Transaction"
5. Success notification appears

### Edit a Category
1. Go to Categories page
2. Find the category in the table
3. Click "Edit" button
4. Modify the category name
5. Click "Update Category"

### Edit a Transaction
1. Go to Transactions page
2. Find the transaction in the table
3. Click "Edit" button
4. Update the fields as needed
5. Click "Update Transaction"

### Delete a Category
1. Go to Categories page
2. Click "Delete" button
3. Confirm in dialog
4. Category is removed

### Delete a Transaction
1. Go to Transactions page
2. Click "Delete" button
3. Confirm in dialog
4. Transaction is removed

## 📋 Form Fields Reference

### Category Form
| Field | Type | Validation | Required |
|-------|------|-----------|----------|
| Name | Text | 3-50 chars | ✅ Yes |

### Transaction Form
| Field | Type | Validation | Required |
|-------|------|-----------|----------|
| Amount | Decimal | > 0 | ✅ Yes |
| Currency | Dropdown | USD/EUR/GBP/INR/JPY/AUD/CAD | ✅ Yes |
| Category | Dropdown | From categories list | ✅ Yes |
| Date | Date | Valid date | ✅ Yes |
| Payment Method | Dropdown | Credit Card/Debit Card/Cash/etc | ✅ Yes |
| Description | Text | 3-200 chars | ✅ Yes |
| Comments | Text | Max 500 chars | ❌ No |

## 🔔 Notifications

The app shows toast notifications for:
- ✅ **Success**: Operation completed successfully
- ❌ **Error**: Operation failed (with reason)
- ⚠️ **Warning**: User action needed
- ℹ️ **Info**: General information

Notifications auto-dismiss after 4 seconds.

## 📱 Mobile Support

All pages are fully responsive:
- Forms stack on mobile
- Tables scroll horizontally
- Navigation adapts
- Touch-friendly buttons

## 🐛 Troubleshooting

### "Failed to load categories/transactions"
- Check if backend is running on `http://localhost:8081`
- Check browser console for errors
- Verify authentication token is valid

### Form validation errors appear
- Ensure all required fields are filled
- Check field value constraints (min/max length, numeric range)
- Look for red error text below fields

### Notifications not showing
- Check if NotificationsComponent is loaded (should be at top-right)
- Clear browser cache and reload
- Check browser console for errors

### Category dropdown empty in transactions
- Make sure at least one category exists
- Create a category first before creating transactions

## 🔗 API Configuration

Backend API URL: `http://localhost:8081`

Located in: `src/app/core/config/api.config.ts`

Change if backend runs on different URL:
```typescript
export const API_CONFIG = {
  baseUrl: 'http://your-new-url:port'
} as const;
```

## 📂 File Structure

```
src/app/
├── categories/           # Category management
├── transactions/         # Transaction management
├── core/
│   ├── models/          # TypeScript interfaces
│   ├── services/        # API services
│   └── config/          # API configuration
├── shared/
│   ├── notifications/   # Toast component
│   ├── header/          # Navigation
│   └── footer/          # Footer
└── home/                # Dashboard
```

## 💾 State Management

Data is managed locally via:
- **BehaviorSubject**: In category and transaction services
- **Signals**: For UI state (loading, form visibility)
- **Observable streams**: For reactive updates

No page refresh needed - data updates automatically.

## 🔐 Authentication

Services automatically use the authentication token from:
- `localStorage.auth_token` (if "Remember Me" was checked)
- `sessionStorage.auth_token` (if "Remember Me" was unchecked)

## 📊 Pagination

Transactions table shows:
- **10 items per page**
- Navigation buttons (Previous/Next)
- Current page indicator
- Auto-disabled buttons at boundaries

## 🎨 Styling

All components follow the design system:
- Primary Color: `#4f46e5` (Indigo)
- Secondary Color: `#8b5cf6` (Purple)
- Accent Color: `#059669` (Green)
- Background: Light mode with subtle shadows
- Typography: System fonts with 1rem base size

## 🧪 Testing the Integration

### Manual Testing Checklist
- [ ] Categories page loads
- [ ] Create category successfully
- [ ] Edit category name
- [ ] Delete category (confirm dialog works)
- [ ] Transactions page loads
- [ ] Create transaction (all fields)
- [ ] Category dropdown populates
- [ ] Edit transaction
- [ ] Delete transaction
- [ ] Pagination works
- [ ] Form validation prevents invalid data
- [ ] Notifications appear for all operations
- [ ] Mobile responsive on small screens

## 📞 Support

For issues:
1. Check browser console (F12)
2. Check Network tab for API calls
3. Verify backend is running
4. Check backend logs for errors
5. Review INTEGRATION_GUIDE.md for detailed info

---

**Need help?** Refer to `INTEGRATION_GUIDE.md` for comprehensive documentation.
