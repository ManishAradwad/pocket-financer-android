import React, { useState } from 'react';
import { 
  CreditCard, 
  Plus, 
  Copy, 
  Check, 
  Eye, 
  EyeOff, 
  TrendingUp, 
  ShieldCheck, 
  ChevronRight, 
  Smartphone, 
  HelpCircle,
  Clock,
  PiggyBank,
  Wallet,
  ArrowUpRight
} from 'lucide-react';
import { motion, AnimatePresence } from 'motion/react';

export interface Account {
  id: string; // Matches transaction.account
  name: string;
  type: 'credit' | 'debit' | 'savings' | 'wallet';
  number: string;
  bank: string;
  color: string; // Tailwind gradient classes
  balance: number; // For credit, this is current outstanding. For savings/debit/wallet, this is available balance.
  limit?: number; // Only for credit cards
  ifsc?: string;
  fullNumber?: string;
}

interface AccountsScreenProps {
  accounts: Account[];
  onAddAccount: (newAcc: Account) => void;
  onFilterAndGoToTxns: (accountId: string) => void;
  transactionsCountByAccount: Record<string, number>;
}

export const AccountsScreen = ({ 
  accounts, 
  onAddAccount, 
  onFilterAndGoToTxns,
  transactionsCountByAccount 
}: AccountsScreenProps) => {
  const [showAddSheet, setShowAddSheet] = useState(false);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [revealSecureId, setRevealSecureId] = useState<string | null>(null);

  // Form states for new Account
  const [bank, setBank] = useState('SBI');
  const [accType, setAccType] = useState<'credit' | 'debit' | 'savings' | 'wallet'>('credit');
  const [name, setName] = useState('');
  const [number, setNumber] = useState('');
  const [balance, setBalance] = useState('');
  const [limit, setLimit] = useState('');
  const [ifsc, setIfsc] = useState('');
  const [gradientChoice, setGradientChoice] = useState('from-[#0F172A] via-[#1E293B] to-[#020617]');

  const GRADIENTS = [
    { name: 'Obsidian Midnight', value: 'from-[#0F172A] via-[#1E293B] to-[#020617]' },
    { name: 'Royal HDFC Blue', value: 'from-[#0B1F48] via-[#103473] to-[#050f24]' },
    { name: 'Spice Crimson', value: 'from-[#2D0B12] via-[#5C1322] to-[#120306]' },
    { name: 'Emerald Vault', value: 'from-[#062413] via-[#0E4926] to-[#021008]' },
    { name: 'Sunset Bronze', value: 'from-[#2D1F10] via-[#4F3316] to-[#140D06]' },
  ];

  const handleCopy = (text: string, label: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(label);
    setTimeout(() => setCopiedId(null), 2000);
  };

  const handleReveal = (id: string) => {
    setRevealSecureId(revealSecureId === id ? null : id);
  };

  const onSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name || !number) return;

    const formattedId = accType === 'credit' 
      ? `Credit Card XX${number}` 
      : accType === 'debit' 
        ? `Debit Card XX${number}` 
        : `A/c XX${number}`;

    const newAcc: Account = {
      id: formattedId,
      name: name || `${bank} Card`,
      type: accType,
      number: `XX${number}`,
      bank: bank,
      color: gradientChoice,
      balance: parseFloat(balance) || 0,
      limit: accType === 'credit' ? (parseFloat(limit) || 100000) : undefined,
      ifsc: ifsc || 'SBIN0001234',
      fullNumber: accType === 'credit' 
        ? `4123 5500 1200 ${number}` 
        : `5020 4012 3901 ${number}`
    };

    onAddAccount(newAcc);
    
    // Reset states
    setName('');
    setNumber('');
    setBalance('');
    setLimit('');
    setIfsc('');
    setShowAddSheet(false);
  };

  // Compute stats
  const totalSavings = accounts
    .filter(a => a.type === 'savings' || a.type === 'debit' || a.type === 'wallet')
    .reduce((sum, a) => sum + a.balance, 0);

  const totalCreditDebt = accounts
    .filter(a => a.type === 'credit')
    .reduce((sum, a) => sum + a.balance, 0);

  const netWealth = totalSavings - totalCreditDebt;

  return (
    <div className="flex-1 overflow-y-auto hide-scrollbar bg-m3-surface text-m3-on-surface flex flex-col pb-6">
      {/* Header */}
      <div className="px-4 pt-4 pb-2 flex items-center justify-between shrink-0">
        <div className="text-lg font-bold tracking-tight text-m3-on-surface font-display">My Accounts</div>
        <button 
          onClick={() => setShowAddSheet(true)}
          className="bg-m3-secondary-container hover:bg-m3-secondary-container/80 text-m3-on-secondary-container px-3 py-1.5 rounded-full text-xs font-semibold flex items-center gap-1 transition-all active:scale-95 border border-m3-secondary-container"
        >
          <Plus size={14} /> Add New
        </button>
      </div>

      {/* Net Asset Card / Wealth Banner */}
      <div className="mx-4 mt-2 mb-5 p-4 rounded-[20px] bg-m3-surface-container-low border border-m3-outline-variant/20 relative overflow-hidden shrink-0">
        <div className="absolute right-0 top-0 w-24 h-24 bg-m3-primary/5 rounded-full blur-2xl pointer-events-none" />
        <div className="flex justify-between items-center mb-1.5">
          <span className="text-[10px] font-bold uppercase tracking-widest text-m3-primary font-display flex items-center gap-1">
            <TrendingUp size={12} /> Live On-Device Combined Worth
          </span>
          <div className="inline-flex items-center gap-1 text-[10px] bg-m3-pos-container/20 text-m3-on-pos-container px-2 py-0.5 rounded-full border border-m3-pos/15">
            <ShieldCheck size={11} strokeWidth={2.5} /> On-Device
          </div>
        </div>
        
        <h2 className={`text-3xl font-extrabold tracking-tight font-display mb-3 ${netWealth >= 0 ? 'text-m3-on-surface' : 'text-m3-error'}`}>
          ₹{netWealth.toLocaleString('en-IN')}
        </h2>

        <div className="grid grid-cols-2 gap-2 pt-2 border-t border-m3-outline-variant/15 text-[11px] font-mono">
          <div>
            <div className="text-m3-on-surface-variant flex items-center gap-1">
              <PiggyBank size={11} className="text-m3-pos" /> Savings / Liquid
            </div>
            <div className="text-m3-on-surface font-semibold mt-0.5">₹{totalSavings.toLocaleString('en-IN')}</div>
          </div>
          <div>
            <div className="text-m3-on-surface-variant flex items-center gap-1">
              <CreditCard size={11} className="text-m3-error" /> CC Outstanding
            </div>
            <div className="text-m3-on-surface font-semibold mt-0.5">₹{totalCreditDebt.toLocaleString('en-IN')}</div>
          </div>
        </div>
      </div>

      {/* Interactive Accounts List representing beautifully styled cards */}
      <div className="space-y-4 px-4 flex-1">
        {accounts.map((acc) => {
          const isCredit = acc.type === 'credit';
          const isRevealed = revealSecureId === acc.id;
          const txnCount = transactionsCountByAccount[acc.id] || 0;

          return (
            <div 
              key={acc.id}
              className="bg-m3-surface-container rounded-[22px] border border-m3-outline-variant/15 overflow-hidden shadow-sm hover:shadow-md transition-shadow"
            >
              {/* Outer Graphical Card */}
              <div className={`p-4 bg-gradient-to-br ${acc.color} text-white relative flex flex-col justify-between h-[155px] select-none border-b border-white/5`}>
                
                {/* Bank / Spec Row */}
                <div className="flex justify-between items-start z-10">
                  <div>
                    <div className="text-[10px] font-bold tracking-widest opacity-70 uppercase font-display">{acc.bank}</div>
                    <div className="text-xs font-semibold tracking-tight mt-0.5 text-white/95">{acc.name}</div>
                  </div>
                  <div className="w-8 h-8 rounded-lg bg-white/10 flex items-center justify-center text-white/90">
                    {acc.type === 'savings' ? <PiggyBank size={18} /> : acc.type === 'wallet' ? <Wallet size={18} /> : <CreditCard size={18} />}
                  </div>
                </div>

                {/* Card Number Representation */}
                <div className="my-auto z-10 pt-2">
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-base tracking-[0.25em] text-white/95">{isRevealed ? acc.fullNumber : `•••• •••• •••• ${acc.number.replace('XX', '') || '9123'}`}</span>
                    <button 
                      onClick={() => handleReveal(acc.id)}
                      className="text-white/60 hover:text-white p-1 rounded-full hover:bg-white/10 transition-colors"
                      title={isRevealed ? "Hide details" : "Show details"}
                    >
                      {isRevealed ? <EyeOff size={13} /> : <Eye size={13} />}
                    </button>
                  </div>
                </div>

                {/* Balance Meter & Outstanding row */}
                <div className="flex justify-between items-end z-10 pt-2">
                  <div>
                    <div className="text-[8px] font-bold tracking-wider opacity-60 uppercase">
                      {isCredit ? 'CREDIT CARD OUTSTANDING' : 'AVAILABLE BALANCE'}
                    </div>
                    <div className="font-mono font-bold text-base mt-0.5">
                      ₹{acc.balance.toLocaleString('en-IN')}
                    </div>
                  </div>
                  
                  {isCredit && acc.limit && (
                    <div className="text-right">
                      <div className="text-[8.5px] font-bold tracking-wider opacity-60 uppercase">CREDIT LIMIT</div>
                      <div className="font-mono text-[11px] opacity-90">₹{acc.limit.toLocaleString('en-IN')}</div>
                    </div>
                  )}
                </div>

                {/* Credit Limit Gauge (Only for CC) */}
                {isCredit && acc.limit && (
                  <div className="absolute bottom-0 left-0 right-0 h-1 bg-black/30">
                    <div 
                      className="h-full bg-m3-primary/90 transition-all duration-500 rounded-r-sm"
                      style={{ width: `${Math.min((acc.balance / acc.limit) * 100, 100)}%` }}
                    />
                  </div>
                )}
              </div>

              {/* Private Quick Access Reference Area & Navigation actions */}
              <div className="p-3 bg-m3-surface-container-low flex flex-col gap-2.5">
                {/* Interactive expandable reference bar if user turns on Details */}
                {isRevealed && (
                  <motion.div 
                    initial={{ opacity: 0, y: -5 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="p-2.5 rounded-xl bg-m3-surface-container border border-m3-outline-variant/20 grid grid-cols-2 gap-2 text-[10px] font-mono leading-none"
                  >
                    <div>
                      <div className="text-m3-on-surface-variant mb-1">ACCOUNT NO.</div>
                      <div className="text-m3-on-surface font-semibold flex items-center gap-1">
                        <span>{acc.type === 'savings' ? acc.fullNumber : `77884421${acc.number.replace('XX', '')}`}</span>
                        <button 
                          onClick={() => handleCopy(acc.type === 'savings' ? (acc.fullNumber || '') : `77884421${acc.number.replace('XX', '')}`, `${acc.id}-no`)}
                          className="text-m3-primary hover:text-m3-primary/85 shrink-0"
                        >
                          {copiedId === `${acc.id}-no` ? <Check size={11} className="text-m3-pos" /> : <Copy size={11} />}
                        </button>
                      </div>
                    </div>
                    <div>
                      <div className="text-m3-on-surface-variant mb-1">BANK IFSC CODE</div>
                      <div className="text-m3-on-surface font-semibold flex items-center gap-1">
                        <span>{acc.ifsc || 'HDFC0000060'}</span>
                        <button 
                          onClick={() => handleCopy(acc.ifsc || 'HDFC0000060', `${acc.id}-ifsc`)}
                          className="text-m3-primary hover:text-m3-primary/85 shrink-0"
                        >
                          {copiedId === `${acc.id}-ifsc` ? <Check size={11} className="text-m3-pos" /> : <Copy size={11} />}
                        </button>
                      </div>
                    </div>
                  </motion.div>
                )}

                {/* Mini utility segment: transaction link */}
                <div className="flex justify-between items-center">
                  <div className="text-[10px] font-mono font-medium text-m3-on-surface-variant flex items-center gap-1 leading-none">
                    <Clock size={11} /> {txnCount} synced messages
                  </div>

                  <button
                    onClick={() => onFilterAndGoToTxns(acc.id)}
                    className="text-[11px] font-semibold text-m3-primary hover:text-m3-primary/90 flex items-center gap-0.5 leading-none"
                  >
                    Explore Txns <ChevronRight size={12} strokeWidth={2.5} />
                  </button>
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {/* Slide-Up Bottom Drawer Sheet to Add New Account */}
      <AnimatePresence>
        {showAddSheet && (
          <div className="absolute inset-x-0 top-0 bottom-[72px] z-50 bg-black/60 backdrop-blur-sm flex flex-col justify-end">
            <motion.div 
              initial={{ y: 350, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              exit={{ y: 350, opacity: 0 }}
              className="bg-m3-surface-container rounded-t-[28px] border-t border-m3-outline-variant/30 flex flex-col max-h-[92%] shadow-[0_-12px_44px_rgba(0,0,0,0.65)] overflow-hidden"
            >
              {/* Drag handles styled */}
              <div className="flex justify-center p-3 shrink-0 cursor-pointer" onClick={() => setShowAddSheet(false)}>
                <div className="w-12 h-1 rounded-full bg-m3-outline-variant/40" />
              </div>

              <div className="px-5 pb-1.5 flex justify-between items-center border-b border-m3-outline-variant/15">
                <span className="text-sm font-bold tracking-tight font-display text-m3-on-surface">Link Reference Account</span>
                <button 
                  onClick={() => setShowAddSheet(false)}
                  className="text-xs font-semibold text-m3-on-surface-variant hover:text-m3-on-surface px-2.5 py-1 bg-m3-surface-container-high rounded-full border border-m3-outline-variant/30 transition-colors"
                >
                  Cancel
                </button>
              </div>

              {/* Form viewport */}
              <form onSubmit={onSubmit} className="p-5 overflow-y-auto space-y-4">
                
                {/* Bank / Type Selector */}
                <div className="grid grid-cols-2 gap-3.5">
                  <div>
                    <label className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant mb-1.5 block">Bank Issuer</label>
                    <select 
                      value={bank} 
                      onChange={(e) => setBank(e.target.value)}
                      className="w-full bg-m3-bg text-xs font-medium text-m3-on-surface p-2.5 rounded-xl border border-m3-outline-variant focus:border-m3-primary outline-none"
                    >
                      <option value="Axis Bank">Axis Bank</option>
                      <option value="HDFC Bank">HDFC Bank</option>
                      <option value="ICICI Bank">ICICI Bank</option>
                      <option value="SBI">State Bank of India</option>
                      <option value="Paytm Network">Paytm Payment</option>
                    </select>
                  </div>
                  <div>
                    <label className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant mb-1.5 block">Account Type</label>
                    <select 
                      value={accType} 
                      onChange={(e) => setAccType(e.target.value as any)}
                      className="w-full bg-m3-bg text-xs font-medium text-m3-on-surface p-2.5 rounded-xl border border-m3-outline-variant focus:border-m3-primary outline-none"
                    >
                      <option value="credit">Credit Card</option>
                      <option value="debit">Debit Card</option>
                      <option value="savings">Savings Account</option>
                      <option value="wallet">Digital Wallet</option>
                    </select>
                  </div>
                </div>

                {/* Nickname */}
                <div>
                  <label className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant mb-1.5 block">Instrument Nickname</label>
                  <input 
                    type="text" 
                    value={name} 
                    onChange={(e) => setName(e.target.value)}
                    required
                    placeholder="e.g. SBI Cashback Card, Salary Account"
                    className="w-full bg-m3-bg text-xs font-medium text-m3-on-surface p-2.5 rounded-xl border border-m3-outline-variant focus:border-m3-primary focus:ring-1 focus:ring-m3-primary outline-none placeholder:text-m3-on-surface-variant/45"
                  />
                </div>

                {/* Number & IFSC */}
                <div className="grid grid-cols-2 gap-3.5">
                  <div>
                    <label className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant mb-1.5 block">Last 4 Digits</label>
                    <input 
                      type="text" 
                      maxLength={4}
                      value={number} 
                      onChange={(e) => setNumber(e.target.value.replace(/\D/g, ''))}
                      required
                      placeholder="e.g. 7788"
                      className="w-full bg-m3-bg text-xs font-mono text-m3-on-surface p-2.5 rounded-xl border border-m3-outline-variant focus:border-m3-primary outline-none placeholder:text-m3-on-surface-variant/45"
                    />
                  </div>
                  <div>
                    <label className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant mb-1.5 block">IFSC Code (Optional)</label>
                    <input 
                      type="text" 
                      maxLength={11}
                      value={ifsc} 
                      onChange={(e) => setIfsc(e.target.value.toUpperCase())}
                      placeholder="SBIN0001234"
                      className="w-full bg-m3-bg text-xs font-mono text-m3-on-surface p-2.5 rounded-xl border border-m3-outline-variant focus:border-m3-primary outline-none placeholder:text-m3-on-surface-variant/45"
                    />
                  </div>
                </div>

                {/* Starting Balance or Limit */}
                <div className="grid grid-cols-2 gap-3.5">
                  <div>
                    <label className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant mb-1.5 block">
                      {accType === 'credit' ? 'Outstanding Amt (₹)' : 'Current Balance (₹)'}
                    </label>
                    <input 
                      type="number" 
                      value={balance} 
                      onChange={(e) => setBalance(e.target.value)}
                      required
                      placeholder="e.g. 1910"
                      className="w-full bg-m3-bg text-xs font-mono text-m3-on-surface p-2.5 rounded-xl border border-m3-outline-variant focus:border-m3-primary outline-none"
                    />
                  </div>
                  {accType === 'credit' && (
                    <div>
                      <label className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant mb-1.5 block">Total Credit Limit (₹)</label>
                      <input 
                        type="number" 
                        value={limit} 
                        onChange={(e) => setLimit(e.target.value)}
                        placeholder="e.g. 150000"
                        className="w-full bg-m3-bg text-xs font-mono text-m3-on-surface p-2.5 rounded-xl border border-m3-outline-variant focus:border-m3-primary outline-none"
                      />
                    </div>
                  )}
                </div>

                {/* Color Selection */}
                <div>
                  <label className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant mb-2 block">Choose Card Visual Theme</label>
                  <div className="flex gap-2 justify-between">
                    {GRADIENTS.map((grad) => (
                      <button
                        key={grad.name}
                        type="button"
                        onClick={() => setGradientChoice(grad.value)}
                        className={`w-9 h-9 rounded-xl bg-gradient-to-br ${grad.value} border-2 transition-all ${gradientChoice === grad.value ? 'border-m3-primary scale-110 shadow-md shadow-m3-primary/10' : 'border-transparent hover:scale-105'}`}
                        title={grad.name}
                      />
                    ))}
                  </div>
                </div>

                <div className="pt-3">
                  <button
                    type="submit"
                    className="w-full py-3 bg-m3-primary text-m3-on-primary font-bold font-display rounded-full flex items-center justify-center gap-2 hover:bg-m3-primary/95 active:scale-[0.98] transition-all shadow-sm shrink-0"
                  >
                    <Plus size={16} /> Link and Sync Account
                  </button>
                  <div className="flex items-center justify-center gap-1.5 mt-3 text-m3-on-surface-variant/75 text-[10px]">
                    <ShieldCheck size={12} className="text-m3-pos" />
                    <span>Reference accounts are kept strictly offline on-device.</span>
                  </div>
                </div>

              </form>
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </div>
  );
};
