export interface DemoAccount {
    id: number;
    name: string;
    type: string;
    currency: string;
    balance: number;
    status: string;
    createdAt: string;
}

export interface DemoAsset {
    id: number;
    name: string;
    symbol: string;
    type: string;
    market: string;
    currency: string;
    quantity: number;
    avgCost: number;
    currentPrice: number;
    marketValue: number;
    profitLoss: number;
    profitLossRate: number;
    createdAt: string;
}

export interface DemoTransaction {
    id: number;
    accountId: number;
    categoryId: number;
    type: 'INCOME' | 'EXPENSE' | 'ADJUSTMENT';
    amount: number;
    currency: string;
    description?: string;
    transactedAt: string;
}

export const demoDashboard = {
    totalAssets: 82468.5,
    netWorth: 76535.2,
    monthIncome: 18500,
    monthExpense: 6928.4,
    monthNet: 11571.6,
    assetAllocation: [
        { name: '银行存款', value: 28350, percentage: 34.4 },
        { name: '基金', value: 24118.5, percentage: 29.2 },
        { name: '股票', value: 17600, percentage: 21.3 },
        { name: '现金与支付', value: 12400, percentage: 15.1 },
    ],
    monthlyCashFlowTrend: [
        { month: '2026-02', income: 16800, expense: 7280, net: 9520 },
        { month: '2026-03', income: 12500, expense: 13980, net: -1480 },
        { month: '2026-04', income: 0, expense: 0, net: 0 },
        { month: '2026-05', income: 17200, expense: 8140, net: 9060 },
        { month: '2026-06', income: 18600, expense: 7450, net: 11150 },
        { month: '2026-07', income: 18500, expense: 6928.4, net: 11571.6 },
    ],
    recentTransactions: [
        { id: 1008, type: 'EXPENSE', amount: 86.5, category: '餐饮', account: '日常储蓄卡', date: '2026-07-12' },
        { id: 1007, type: 'INCOME', amount: 18500, category: '工资', account: '日常储蓄卡', date: '2026-07-10' },
        { id: 1006, type: 'EXPENSE', amount: 1280, category: '居住', account: '日常储蓄卡', date: '2026-07-05' },
        { id: 1005, type: 'EXPENSE', amount: 398, category: '学习', account: '支付宝', date: '2026-07-03' },
        { id: 1004, type: 'ADJUSTMENT', amount: -45, category: '余额调整', account: '现金钱包', date: '2026-07-01' },
    ],
};

export const initialDemoAccounts: DemoAccount[] = [
    { id: 1, name: '日常储蓄卡', type: 'BANK', currency: 'CNY', balance: 28350, status: 'ACTIVE', createdAt: '2026-01-01T09:00:00' },
    { id: 2, name: '支付宝', type: 'PAYMENT_PLATFORM', currency: 'CNY', balance: 2400, status: 'ACTIVE', createdAt: '2026-01-01T09:00:00' },
    { id: 3, name: '现金钱包', type: 'CASH', currency: 'CNY', balance: 800, status: 'ACTIVE', createdAt: '2026-01-01T09:00:00' },
    { id: 4, name: '信用卡', type: 'CREDIT_CARD', currency: 'CNY', balance: -1933.3, status: 'ACTIVE', createdAt: '2026-02-12T09:00:00' },
    { id: 5, name: '证券账户', type: 'BROKERAGE', currency: 'CNY', balance: 18000, status: 'ACTIVE', createdAt: '2026-03-01T09:00:00' },
];

export const initialDemoAssets: DemoAsset[] = [
    { id: 1, name: '沪深 300 ETF', symbol: '510300', type: 'FUND', market: '上海', currency: 'CNY', quantity: 3200, avgCost: 3.72, currentPrice: 3.91, marketValue: 12512, profitLoss: 608, profitLossRate: 5.11, createdAt: '2026-02-18T09:30:00' },
    { id: 2, name: '中证红利 ETF', symbol: '515180', type: 'FUND', market: '上海', currency: 'CNY', quantity: 2800, avgCost: 1.24, currentPrice: 1.31, marketValue: 3668, profitLoss: 196, profitLossRate: 5.65, createdAt: '2026-03-12T09:30:00' },
    { id: 3, name: '宁德时代', symbol: '300750', type: 'STOCK', market: '深圳', currency: 'CNY', quantity: 80, avgCost: 205, currentPrice: 220, marketValue: 17600, profitLoss: 1200, profitLossRate: 7.32, createdAt: '2026-04-08T09:30:00' },
    { id: 4, name: '货币基金', symbol: 'DEMO-MF', type: 'FUND', market: '场外', currency: 'CNY', quantity: 7938.5, avgCost: 1, currentPrice: 1, marketValue: 7938.5, profitLoss: 0, profitLossRate: 0, createdAt: '2026-01-15T09:30:00' },
];

export const demoCategories = [
    { id: 1, name: '工资', type: 'INCOME' },
    { id: 2, name: '奖金', type: 'INCOME' },
    { id: 3, name: '餐饮', type: 'EXPENSE' },
    { id: 4, name: '居住', type: 'EXPENSE' },
    { id: 5, name: '学习', type: 'EXPENSE' },
    { id: 6, name: '交通', type: 'EXPENSE' },
    { id: 7, name: '余额调整', type: 'ADJUSTMENT' },
];

export const initialDemoTransactions: DemoTransaction[] = [
    { id: 1008, accountId: 1, categoryId: 3, type: 'EXPENSE', amount: 86.5, currency: 'CNY', description: '周末餐饮', transactedAt: '2026-07-12T12:35:00' },
    { id: 1007, accountId: 1, categoryId: 1, type: 'INCOME', amount: 18500, currency: 'CNY', description: '七月工资', transactedAt: '2026-07-10T09:00:00' },
    { id: 1006, accountId: 1, categoryId: 4, type: 'EXPENSE', amount: 1280, currency: 'CNY', description: '房租', transactedAt: '2026-07-05T10:00:00' },
    { id: 1005, accountId: 2, categoryId: 5, type: 'EXPENSE', amount: 398, currency: 'CNY', description: '在线课程', transactedAt: '2026-07-03T20:15:00' },
    { id: 1004, accountId: 3, categoryId: 7, type: 'ADJUSTMENT', amount: -45, currency: 'CNY', description: '零钱盘点', transactedAt: '2026-07-01T18:00:00' },
    { id: 1003, accountId: 1, categoryId: 6, type: 'EXPENSE', amount: 156, currency: 'CNY', description: '出行交通', transactedAt: '2026-06-28T18:30:00' },
    { id: 1002, accountId: 1, categoryId: 2, type: 'INCOME', amount: 1200, currency: 'CNY', description: '项目奖金', transactedAt: '2026-06-20T10:00:00' },
    { id: 1001, accountId: 2, categoryId: 3, type: 'EXPENSE', amount: 72, currency: 'CNY', description: '午餐', transactedAt: '2026-06-18T12:20:00' },
];
