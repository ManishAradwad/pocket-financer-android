import React, { useState } from 'react';
import { Home, List, PieChart, Settings, CreditCard, Shield, RefreshCw, Trash2, Info } from 'lucide-react';
import { HomeScreen } from './components/HomeScreen';
import { TransactionsScreen } from './components/TransactionsScreen';
import { InsightsScreen } from './components/InsightsScreen';
import { OnboardingFlow } from './components/OnboardingFlow';
import { AccountsScreen, Account } from './components/AccountsScreen';
import { TXNS } from './data';

export interface UnsyncedSms {
  id: string;
  sender: string;
  body: string;
  date: string;
  time: string;
  status: 'pending' | 'processing' | 'filtered_out' | 'synced';
  stages: {
    name: string;
    description: string;
    status: 'idle' | 'running' | 'completed' | 'failed';
    metrics?: string;
  }[];
  parsedData?: {
    payee: string;
    amount: number;
    account: string;
    type: 'credit' | 'debit';
  };
}

const INITIAL_ACCOUNTS: Account[] = [
  {
    id: "Credit Card XX7788",
    name: "Axis MyZone Credit Card",
    type: "credit",
    number: "XX7788",
    bank: "Axis Bank",
    color: "from-[#1a103c] via-[#2d1b54] to-[#120726]",
    balance: 1910,
    limit: 50000,
    ifsc: "UTIB0000234",
    fullNumber: "4314 9122 8847 7788"
  },
  {
    id: "Debit Card XX4521",
    name: "HDFC EasyShop Platinum",
    type: "debit",
    number: "XX4521",
    bank: "HDFC Bank",
    color: "from-[#081e4c] via-[#103473] to-[#040e24]",
    balance: 42810.55,
    ifsc: "HDFC0000060",
    fullNumber: "4521 8892 1010 4521"
  },
  {
    id: "A/c XX6254",
    name: "HDFC Max Savings Check",
    type: "savings",
    number: "XX6254",
    bank: "HDFC Bank",
    color: "from-[#112330] via-[#1b3547] to-[#091520]",
    balance: 93250,
    ifsc: "HDFC0000060",
    fullNumber: "50100239486254"
  }
];

const INITIAL_UNSYNCED_SMS: UnsyncedSms[] = [
  {
    id: "sms-1",
    sender: "AX-AXISBK",
    body: "Dear Customer, INR 1,250.00 debited from Axis Bank Credit Card XX7788 at ZEPTOWORLD PL on 25-Apr-2026. Bal limit: ₹45,130.",
    date: "22-04-2026",
    time: "21:30",
    status: 'pending',
    stages: [
      { name: "Non-Txn Filter", description: "Filtering spam & promo notifications", status: 'idle' },
      { name: "Bank Router ID", description: "Identifying Axis Bank issuer & card match", status: 'idle' },
      { name: "Token Analysis", description: "SLM (Qwen 1.7B) structured key extraction", status: 'idle' },
      { name: "Ledger Matcher", description: "Mapping destination ledger profile", status: 'idle' },
      { name: "On-device Sync", description: "Composing local DB transaction object", status: 'idle' }
    ],
    parsedData: {
      payee: "ZEPTOWORLD PL",
      amount: 1250,
      account: "Credit Card XX7788",
      type: "debit"
    }
  },
  {
    id: "sms-2",
    sender: "AD-ZEPTOLIT",
    body: "GET 40% OFF up to ₹150 on your late night craving! Use coupon LATE40 on Zepto Cafe. Hurry, valid till midnight!",
    date: "22-04-2026",
    time: "19:40",
    status: 'pending',
    stages: [
      { name: "Non-Txn Filter", description: "Filtering promotional SMS spam (99.7% confidence)", status: 'idle' }
    ]
  },
  {
    id: "sms-3",
    sender: "VM-HDFCBK",
    body: "HDFC Bank: Alert! You spent ₹890.00 at AMZN Prime on Debit Card XX4521. Avail Bal: ₹41,920.55 on 22-Apr-2026.",
    date: "22-04-2026",
    time: "15:20",
    status: 'pending',
    stages: [
      { name: "Non-Txn Filter", description: "Filtering spam & promo notifications", status: 'idle' },
      { name: "Bank Router ID", description: "Identifying HDFC Bank issuer & card match", status: 'idle' },
      { name: "Token Analysis", description: "SLM (Qwen 1.7B) structured key extraction", status: 'idle' },
      { name: "Ledger Matcher", description: "Mapping destination ledger profile", status: 'idle' },
      { name: "On-device Sync", description: "Composing local DB transaction object", status: 'idle' }
    ],
    parsedData: {
      payee: "AMZN Prime",
      amount: 890,
      account: "Debit Card XX4521",
      type: "debit"
    }
  }
];

export default function App() {
  const [tab, setTab] = useState(0);
  const [showOnboarding, setShowOnboarding] = useState<boolean>(true);
  const [accounts, setAccounts] = useState<Account[]>(INITIAL_ACCOUNTS);
  const [selectedAccountFilter, setSelectedAccountFilter] = useState<string>('All');
  const [transactionsHistory, setTransactionsHistory] = useState<any[]>(TXNS);

  // Sync / local SLM parsing state
  const [syncState, setSyncState] = useState<'pending' | 'syncing' | 'done'>('pending');
  const [unsyncedSmsList, setUnsyncedSmsList] = useState<UnsyncedSms[]>(INITIAL_UNSYNCED_SMS);
  const [currentSmsIndex, setCurrentSmsIndex] = useState<number | null>(null);
  const [currentStageIndex, setCurrentStageIndex] = useState<number | null>(null);

  // Interactive toggle states for Settings
  const [autoSync, setAutoSync] = useState(true);
  const [developerLogs, setDeveloperLogs] = useState(false);

  const handleOnboardingComplete = () => {
    setShowOnboarding(false);
  };

  const goToTxns = () => {
    setSelectedAccountFilter('All');
    setTab(2); // Shuffled transactions to tab index 2
  };

  const handleAddAccount = (newAcc: Account) => {
    setAccounts(prev => [newAcc, ...prev]);
  };

  const handleFilterAndGoToTxns = (accountId: string) => {
    setSelectedAccountFilter(accountId);
    setTab(2); // Transition to Transactions Screen
  };

  // Run structured mock processing
  const startSmsProcessing = () => {
    if (syncState !== 'pending') return;
    setSyncState('syncing');
    setCurrentSmsIndex(0);
    setCurrentStageIndex(0);

    let activeSms = 0;
    let activeStage = 0;

    const timer = setInterval(() => {
      if (activeSms >= INITIAL_UNSYNCED_SMS.length) {
        clearInterval(timer);
        setSyncState('done');
        setCurrentSmsIndex(null);
        setCurrentStageIndex(null);
        return;
      }

      const localSmsIndex = activeSms;
      const localStageIndex = activeStage;

      setCurrentSmsIndex(localSmsIndex);
      setCurrentStageIndex(localStageIndex);

      // Deep copy to update live statuses in real-time
      setUnsyncedSmsList(prevList => {
        const copy = JSON.parse(JSON.stringify(prevList)) as UnsyncedSms[];
        const sms = copy[localSmsIndex];
        if (sms) {
          sms.status = 'processing';
          sms.stages.forEach((st, idx) => {
            if (idx === localStageIndex) {
              st.status = 'running';
            } else if (idx < localStageIndex) {
              st.status = 'completed';
            } else {
              st.status = 'idle';
            }
          });
        }
        return copy;
      });

      // Advance
      const maxStages = localSmsIndex === 1 ? 1 : 5; // index 1 is promotional (1 stage filter duration)
      if (activeStage < maxStages - 1) {
        activeStage++;
      } else {
        // Log finished stage outcomes
        const finishedSms = INITIAL_UNSYNCED_SMS[localSmsIndex];

        if (localSmsIndex === 1) {
          // Promo filter completes
          setUnsyncedSmsList(prevList => {
            const copy = JSON.parse(JSON.stringify(prevList)) as UnsyncedSms[];
            if (copy[localSmsIndex]) {
              copy[localSmsIndex].status = 'filtered_out';
              if (copy[localSmsIndex].stages[0]) {
                copy[localSmsIndex].stages[0].status = 'completed';
                copy[localSmsIndex].stages[0].metrics = "Confidence 99% (promotional spam)";
              }
            }
            return copy;
          });
        } else if (finishedSms && finishedSms.parsedData) {
          const parsed = finishedSms.parsedData;
          const newTx = {
            id: Date.now() + localSmsIndex,
            counterparty: parsed.payee,
            account: parsed.account,
            amount: parsed.amount,
            type: parsed.type,
            date: finishedSms.date,
            time: finishedSms.time,
            sender: finishedSms.sender,
            raw: finishedSms.body
          };

          // Append to dynamic transactions history state
          setTransactionsHistory(prev => [newTx, ...prev]);

          // Subtract/Add to account balance
          setAccounts(prevAccs => {
            return prevAccs.map(acc => {
              if (acc.id === parsed.account) {
                if (parsed.type === 'debit') {
                  const updatedBalance = acc.type === 'credit' 
                    ? acc.balance + parsed.amount 
                    : acc.balance - parsed.amount;
                  return {
                    ...acc,
                    balance: updatedBalance
                  };
                }
              }
              return acc;
            });
          });

          setUnsyncedSmsList(prevList => {
            const copy = JSON.parse(JSON.stringify(prevList)) as UnsyncedSms[];
            if (copy[localSmsIndex]) {
              copy[localSmsIndex].status = 'synced';
              copy[localSmsIndex].stages.forEach(st => st.status = 'completed');
            }
            return copy;
          });
        }

        activeSms++;
        activeStage = 0;
      }
    }, 1300);
  };

  // Dynamically calculate on-device synced messages for each account from real transaction history
  const transactionsCountByAccount = accounts.reduce((acc, current) => {
    acc[current.id] = transactionsHistory.filter(t => t.account === current.id).length;
    return acc;
  }, {} as Record<string, number>);

  const screens = [
    <HomeScreen 
      goToTxns={goToTxns}
      syncState={syncState}
      startSmsProcessing={startSmsProcessing}
      unsyncedSmsList={unsyncedSmsList}
      currentSmsIndex={currentSmsIndex}
      currentStageIndex={currentStageIndex}
      onNavigateToTab={(idx) => setTab(idx)}
    />,
    <AccountsScreen 
      accounts={accounts} 
      onAddAccount={handleAddAccount}
      onFilterAndGoToTxns={handleFilterAndGoToTxns}
      transactionsCountByAccount={transactionsCountByAccount}
    />,
    <TransactionsScreen 
      selectedAccountFilter={selectedAccountFilter} 
      setSelectedAccountFilter={setSelectedAccountFilter}
      accounts={accounts}
      transactionsHistory={transactionsHistory}
      syncState={syncState}
      unsyncedSmsList={unsyncedSmsList}
      currentSmsIndex={currentSmsIndex}
      currentStageIndex={currentStageIndex}
    />,
    <InsightsScreen />,
    
    // Polished on-device Settings Screen
    <div className="flex-1 overflow-y-auto hide-scrollbar bg-m3-surface text-m3-on-surface p-4 flex flex-col pb-[72px]">
      <div className="text-lg font-bold tracking-tight text-m3-on-surface font-display mb-4">Settings</div>
      
      {/* On-device status badge */}
      <div className="p-3.5 rounded-2xl bg-m3-surface-container border border-m3-outline-variant/35 mb-5 flex items-start gap-3">
        <Shield size={20} className="text-m3-pos shrink-0 mt-0.5" />
        <div>
          <h4 className="text-xs font-bold text-m3-on-surface font-display">Local Parsing Activated</h4>
          <p className="text-[10px] text-m3-on-surface-variant leading-relaxed mt-1">pocketFinancer utilizes a local Qwen 1.7B parameter deep network using a customized tf-lite/llama.cpp binary compiling natively directly on your device CPU. No queries are forwarded to servers.</p>
        </div>
      </div>

      <div className="space-y-4">
        {/* Sync Controls */}
        <div className="bg-m3-surface-container-low rounded-[20px] p-4 border border-m3-outline-variant/20">
          <h4 className="text-xs font-bold uppercase tracking-wider text-m3-on-surface-variant font-display mb-3">Background Extraction</h4>
          
          <div className="flex justify-between items-center py-2.5 border-b border-m3-outline-variant/10">
            <div>
              <div className="text-xs font-semibold text-m3-on-surface">Auto-Sync on incoming SMS</div>
              <div className="text-[10px] text-m3-on-surface-variant mt-0.5">Launches parsing engine silently on receipt</div>
            </div>
            <input 
              type="checkbox" 
              checked={autoSync}
              onChange={(e) => setAutoSync(e.target.checked)}
              className="w-8 h-4 rounded-full bg-m3-surface-container-highest border-none focus:ring-0 accent-m3-primary cursor-pointer"
            />
          </div>

          <div className="flex justify-between items-center py-2.5">
            <div>
              <div className="text-xs font-semibold text-m3-on-surface">Offline Dev Logs</div>
              <div className="text-[10px] text-m3-on-surface-variant mt-0.5">Save compiler raw logs to on-disk buffer</div>
            </div>
            <input 
              type="checkbox" 
              checked={developerLogs}
              onChange={(e) => setDeveloperLogs(e.target.checked)}
              className="w-8 h-4 rounded-full bg-m3-surface-container-highest border-none focus:ring-0 accent-m3-primary cursor-pointer"
            />
          </div>
        </div>

        {/* Engine reset */}
        <div className="bg-m3-surface-container-low rounded-[20px] p-4 border border-m3-outline-variant/20">
          <h4 className="text-xs font-bold uppercase tracking-wider text-m3-on-surface-variant font-display mb-3">Storage & Memory</h4>
          
          <button 
            type="button"
            onClick={() => {
              setAccounts(INITIAL_ACCOUNTS);
              setTransactionsHistory(TXNS);
              setSyncState('pending');
              setUnsyncedSmsList(INITIAL_UNSYNCED_SMS);
              setCurrentSmsIndex(null);
              setCurrentStageIndex(null);
              alert("Mock local state and on-device transaction caches restored!");
            }}
            className="w-full py-2.5 bg-m3-surface-container hover:bg-m3-surface-container-high text-xs font-semibold rounded-xl border border-m3-outline-variant/40 flex items-center justify-center gap-1.5 transition-colors"
          >
            <RefreshCw size={14} /> Factory Reset Local State
          </button>
        </div>

        {/* Info label */}
        <div className="flex items-center justify-center gap-1 text-[10px] text-m3-on-surface-variant/70 text-center font-mono py-4">
          <Info size={11} /> pocketFinancer v0.8.2-A (beta-local)
        </div>
      </div>
    </div>,
  ];

  const getScreenClass = (i: number) => {
    if (i === tab) return 'absolute inset-0 flex flex-col transition-all duration-200 ease-out opacity-100 translate-x-0 pb-[72px]';
    if (i < tab) return 'absolute inset-0 flex flex-col transition-all duration-200 ease-out opacity-0 -translate-x-[20px] pointer-events-none pb-[72px]';
    return 'absolute inset-0 flex flex-col transition-all duration-200 ease-out opacity-0 translate-x-[20px] pointer-events-none pb-[72px]';
  };

  return (
    <div className="min-h-screen bg-black flex items-center justify-center p-4 font-sans select-none overflow-hidden">
      {/* Mobile Container (Mimics Phone) */}
      <div className="w-[360px] h-[760px] max-w-full max-h-[100vh] rounded-[44px] shadow-[0_40px_80px_rgba(0,0,0,0.6),_0_0_0_1px_#1a1e28] overflow-hidden relative border-[2px] border-zinc-800 flex flex-col bg-m3-bg">
        
        {showOnboarding ? (
          <OnboardingFlow onComplete={handleOnboardingComplete} />
        ) : (
          <>
            {/* Status Bar Mock */}
            <div className="h-[30px] w-full flex justify-between items-center px-6 text-[11px] font-bold text-m3-on-surface shrink-0 z-50">
              <span>9:41</span>
              <div className="flex gap-1.5 items-center opacity-80 text-[10px] tracking-widest text-m3-on-surface-variant">
                ▲▼ ))) ▮▮▮
              </div>
            </div>

            {/* Content Area */}
            <div className="flex-1 relative overflow-hidden">
              {screens.map((screen, i) => (
                <div key={i} className={getScreenClass(i)}>
                  {screen}
                </div>
              ))}
            </div>

            {/* Bottom Navigation (MD3 Navigation Bar expanded to 5 tabs with Accounts) */}
            <div className="absolute bottom-0 left-0 right-0 h-[72px] bg-m3-surface-container flex items-center justify-around px-2 z-40 pb-2 pt-1">
              {[
                { icon: Home, label: 'Home' },
                { icon: CreditCard, label: 'Accounts' },
                { icon: List, label: 'Txns' },
                { icon: PieChart, label: 'Insights' },
                { icon: Settings, label: 'Settings' },
              ].map((item, i) => {
                const active = tab === i;
                return (
                  <div
                    key={i}
                    className="flex flex-col items-center gap-1 cursor-pointer w-[60px] group"
                    onClick={() => setTab(i)}
                  >
                    <div 
                      className={`w-12 h-7.5 rounded-full flex items-center justify-center transition-all duration-200 ${
                        active ? 'bg-m3-secondary-container text-m3-on-secondary-container' : 'text-m3-on-surface-variant hover:bg-m3-on-surface-variant/10'
                      }`}
                    >
                      <item.icon size={20} strokeWidth={active ? 2.5 : 2} className={active ? 'fill-m3-on-secondary-container/20' : ''} />
                    </div>
                    <span 
                      className={`text-[10px] font-semibold tracking-wide transition-colors duration-200 ${
                        active ? 'text-m3-on-surface' : 'text-m3-on-surface-variant'
                      }`}
                    >
                      {item.label}
                    </span>
                  </div>
                );
              })}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
