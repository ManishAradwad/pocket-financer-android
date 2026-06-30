import React, { useState } from 'react';
import { 
  Search, 
  AlignLeft, 
  ArrowUpRight, 
  ArrowDownRight, 
  CreditCard, 
  ChevronLeft, 
  Cpu, 
  Zap, 
  Sparkles, 
  Clock, 
  Binary, 
  Share2, 
  CheckCircle2, 
  ShieldCheck 
} from 'lucide-react';
import { initials, avColor, isVpa } from '../data';
import { motion, AnimatePresence } from 'motion/react';

export interface TransactionScreenProps {
  selectedAccountFilter?: string;
  setSelectedAccountFilter?: React.Dispatch<React.SetStateAction<string>>;
  accounts?: Array<{ id: string; name: string; type: string; bank: string; color: string; number: string }>;
  transactionsHistory?: any[];
  syncState?: 'pending' | 'syncing' | 'done';
  unsyncedSmsList?: any[];
  currentSmsIndex?: number | null;
  currentStageIndex?: number | null;
}

export const TransactionsScreen = ({
  selectedAccountFilter = 'All',
  setSelectedAccountFilter = () => {},
  accounts = [],
  transactionsHistory = [],
  syncState = 'pending',
  unsyncedSmsList = [],
  currentSmsIndex = null,
  currentStageIndex = null
}: TransactionScreenProps) => {
  const [segment, setSegment] = useState('All');
  const [selected, setSelected] = useState<any>(null);
  const [selectedProcessing, setSelectedProcessing] = useState<any>(null);

  const filtered = transactionsHistory.filter((t) => {
    const matchesSegment = segment === 'All' || (segment === 'Debits' && t.type === 'debit') || (segment === 'Credits' && t.type === 'credit');
    const matchesAccount = selectedAccountFilter === 'All' || t.account === selectedAccountFilter;
    return matchesSegment && matchesAccount;
  });

  const groups = [
    { date: '22-04-2026', label: 'Today · Fri, 25 Apr', today: true, txns: filtered.filter((t) => t.date === '22-04-2026') },
    { date: '21-04-2026', label: 'Mon, 21 Apr', today: false, txns: filtered.filter((t) => t.date === '21-04-2026') },
  ].filter((g) => g.txns.length > 0);

  const monthTotal = filtered.filter((t) => t.type === 'debit').reduce((s, t) => s + t.amount, 0);

  // Quick formatter for card nick in chip
  const getAccountShortName = (accId: string, accList: any[]) => {
    const found = accList.find(a => a.id === accId);
    if (!found) return accId.replace('Credit Card ', '').replace('Debit Card ', '').replace('A/c ', '');
    const bank = found.bank;
    return `${bank} ${found.number}`;
  };

  // Check if we have an active transaction under processing right now
  const processedCount = unsyncedSmsList.filter(s => s.status === 'synced' || s.status === 'filtered_out').length;
  const activeSms = currentSmsIndex !== null ? unsyncedSmsList[currentSmsIndex] : null;
  const isSmsProcessingTxn = activeSms && activeSms.id !== 'sms-2'; // sms-2 is promotional spam, not transactional

  // Attention scores simulation to render custom model matrix
  const simulatedAttentionWeights = activeSms?.id === 'sms-1' ? [
    { token: "INR 1,250.00", weight: 0.994, field: "Amount/Value" },
    { token: "ZEPTOWORLD PL", weight: 0.981, field: "Merchant/Counterparty" },
    { token: "XX7788", weight: 0.954, field: "Issuer Ledger Suffix" }
  ] : [
    { token: "₹890.00", weight: 0.997, field: "Amount/Value" },
    { token: "AMZN Prime", weight: 0.989, field: "Merchant/Counterparty" },
    { token: "XX4521", weight: 0.942, field: "Issuer Ledger Suffix" }
  ];

  return (
    <>
      <div className="flex-1 overflow-y-auto hide-scrollbar bg-m3-surface text-m3-on-surface flex flex-col">
        {/* Header */}
        <div className="px-4 pt-4 pb-1 flex items-center justify-between shrink-0">
          <div className="text-lg font-bold tracking-tight text-m3-on-surface font-display">Transactions</div>
          <div className="flex items-center gap-2">
            <button className="w-10 h-10 rounded-full bg-m3-surface-container flex items-center justify-center text-m3-on-surface hover:bg-m3-surface-container-high transition-colors">
              <Search size={20} strokeWidth={2} />
            </button>
            <button className="w-10 h-10 rounded-full bg-m3-surface-container flex items-center justify-center text-m3-on-surface hover:bg-m3-surface-container-high transition-colors">
              <AlignLeft size={20} strokeWidth={2} />
            </button>
          </div>
        </div>

        {/* Segmented Control */}
        <div className="mx-4 my-1.5 p-1 bg-m3-surface-container-lowest flex rounded-full border border-m3-outline-variant/30 shrink-0">
          {['All', 'Debits', 'Credits'].map((s) => (
            <button
              key={s}
              onClick={() => setSegment(s)}
              className={`flex-1 py-1.5 rounded-full text-xs font-medium tracking-wide transition-all duration-200 ${
                segment === s ? 'bg-m3-secondary-container text-m3-on-secondary-container shadow-sm' : 'text-m3-on-surface-variant hover:text-m3-on-surface hover:bg-m3-surface-container-low'
              }`}
            >
              {s}
            </button>
          ))}
        </div>

        {/* Dynamic Accounts Filter Chips Row */}
        <div className="px-4 py-1.5 overflow-x-auto hide-scrollbar flex items-center gap-2 shrink-0 border-b border-m3-outline-variant/10">
          <button
            onClick={() => setSelectedAccountFilter('All')}
            className={`px-3.5 py-1.5 rounded-full text-[11px] font-semibold tracking-wide whitespace-nowrap transition-all flex items-center gap-1.5 ${
              selectedAccountFilter === 'All'
                ? 'bg-m3-primary text-m3-on-primary shadow-sm shadow-m3-primary/10'
                : 'bg-m3-surface-container-low text-m3-on-surface-variant border border-m3-outline-variant/30 hover:bg-m3-surface-container'
            }`}
          >
            All Accounts
          </button>
          {accounts.map((acc) => {
            const isSelected = selectedAccountFilter === acc.id;
            return (
              <button
                key={acc.id}
                onClick={() => setSelectedAccountFilter(acc.id)}
                className={`px-3.5 py-1.5 rounded-full text-[11px] font-semibold tracking-wide whitespace-nowrap transition-all flex items-center gap-1.5 border ${
                  isSelected
                    ? 'bg-m3-surface-container-high text-m3-on-surface border-m3-primary font-bold shadow-sm'
                    : 'bg-m3-surface-container-low text-m3-on-surface-variant border border-m3-outline-variant/30 hover:bg-m3-surface-container'
                }`}
              >
                <span className={`w-2 h-2 rounded-full bg-gradient-to-tr ${acc.color}`} />
                <span>{getAccountShortName(acc.id, accounts)}</span>
              </button>
            );
          })}
        </div>

        {/* Month Header */}
        <div className="px-4 py-2.5 flex items-center justify-between border-b border-m3-outline-variant/20 shrink-0">
          <span className="text-xs font-bold font-display uppercase tracking-wider text-m3-on-surface-variant">April 2026</span>
          <span className="text-xs font-medium text-m3-on-surface-variant flex items-center gap-1 font-mono">
            OUTFLOW <span className="text-m3-primary text-sm">₹</span>
            <span className="text-sm">{monthTotal.toLocaleString('en-IN')}</span>
          </span>
        </div>

        {/* Empty States / Grouped List viewport */}
        <div className="flex-1 overflow-y-auto hide-scrollbar">
          {/* UNDER PROCESSING SMS CARD IMPLEMENTED HERE */}
          {syncState === 'syncing' && isSmsProcessingTxn && (
            <motion.div 
              initial={{ scale: 0.95, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              className="mx-4 mt-3 p-3.5 rounded-[20px] bg-gradient-to-r from-amber-500/10 via-amber-500/5 to-m3-surface-container border border-amber-500/30 shadow-sm cursor-pointer relative overflow-hidden"
              onClick={() => setSelectedProcessing(activeSms)}
            >
              <div className="absolute right-0 top-0 w-20 h-20 bg-amber-500/5 rounded-full blur-xl animate-pulse pointer-events-none" />
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-amber-500/10 border border-amber-500/20 flex items-center justify-center text-amber-500 shrink-0 animate-pulse">
                  <Cpu size={18} className="animate-spin" />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono tracking-widest text-amber-500 font-bold uppercase">Local pipeline engine running</span>
                    <span className="text-[8px] font-mono px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-500 font-bold animate-ping">PROCESSING</span>
                  </div>
                  <h4 className="text-sm font-bold text-m3-on-surface truncate mt-1">Pending from {activeSms?.sender}</h4>
                  <p className="text-[10px] text-m3-on-surface-variant font-mono truncate mt-0.5">{activeSms?.body}</p>
                </div>
              </div>
              <div className="mt-3.5 pt-2.5 border-t border-m3-outline-variant/15 flex items-center justify-between">
                <div className="text-[9px] font-mono text-m3-on-surface-variant/80">
                  SLM Stage: <span className="text-amber-500 font-bold">{activeSms?.stages[currentStageIndex || 0]?.name}</span>
                </div>
                <div className="text-[9px] font-bold text-m3-primary flex items-center gap-0.5">
                  View extraction logs <Zap size={10} fill="currentColor" />
                </div>
              </div>
            </motion.div>
          )}

          {groups.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 px-6 text-center">
              <div className="w-12 h-12 rounded-full bg-m3-surface-container flex items-center justify-center text-m3-on-surface-variant/60 mb-3 border border-m3-outline-variant/30">
                <CreditCard size={20} />
              </div>
              <h3 className="text-sm font-bold text-m3-on-surface font-display">No Transactions Private to Device</h3>
              <p className="text-xs text-m3-on-surface-variant mt-1.5 max-w-[240px] leading-relaxed">
                Our local Qwen SLM hasn't processed any SMS containing matches to this filter pair.
              </p>
            </div>
          ) : (
            groups.map((g) => {
              const dd = g.txns.filter((t) => t.type === 'debit').reduce((s, t) => s + t.amount, 0);
              const dc = g.txns.filter((t) => t.type === 'credit').reduce((s, t) => s + t.amount, 0);
              return (
                <div key={g.date}>
                  <div className="px-4 py-2 flex items-center justify-between border-b border-m3-outline-variant/20 bg-m3-surface/95 sticky top-0 backdrop-blur-md z-10">
                    <span className={`text-[10px] font-bold tracking-wider uppercase ${g.today ? 'text-m3-primary' : 'text-m3-on-surface-variant'}`}>{g.label}</span>
                    <div className="flex items-center gap-1.5">
                      {dd > 0 && (
                        <span className="px-2 py-0.5 rounded-md bg-m3-error-container text-m3-on-error-container text-[10px] font-mono animate-fade-in">
                          ↓ ₹{dd.toLocaleString('en-IN')}
                        </span>
                      )}
                      {dc > 0 && (
                        <span className="px-2 py-0.5 rounded-md bg-m3-pos-container text-m3-on-pos-container text-[10px] font-mono animate-fade-in">
                          ↑ ₹{dc.toLocaleString('en-IN')}
                        </span>
                      )}
                    </div>
                  </div>
                  {g.txns.map((t) => {
                    const c = avColor(t.counterparty || '?');
                    return (
                      <div
                        key={t.id}
                        className="flex items-center gap-3 px-4 py-3 border-b border-m3-outline-variant/20 cursor-pointer hover:bg-m3-surface-container-high transition-colors"
                        onClick={() => setSelected(t)}
                      >
                        <div className={`w-10 h-10 rounded-full flex items-center justify-center text-sm font-medium shrink-0 ${c.bg} ${c.fg}`}>
                          {initials(t.counterparty)}
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="truncate text-sm font-semibold text-m3-on-surface">
                            {t.counterparty}
                          </div>
                          <div className="inline-flex mt-0.5 px-2 py-0.5 rounded text-m3-on-surface-variant font-mono text-[9px] uppercase tracking-wider bg-m3-surface-container-high leading-none">
                            {t.account}
                          </div>
                        </div>
                        <div className="text-right shrink-0">
                          <div className={`text-sm font-bold font-mono ${t.type === 'credit' ? 'text-m3-pos' : 'text-m3-on-surface'}`}>
                            {t.type === 'debit' ? '−' : '+'}₹{t.amount.toLocaleString('en-IN')}
                          </div>
                          <div className="text-[10px] text-m3-on-surface-variant mt-0.5">{t.time}</div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              );
            })
          )}
          <div className="h-6" />
        </div>
      </div>

      {/* Detail Sheet for Completed Transactions */}
      {selected && (() => {
        const t = selected;
        const c = avColor(t.counterparty || '?');
        return (
          <div className="absolute inset-x-0 top-0 bottom-[72px] z-50 bg-black/60 backdrop-blur-sm flex flex-col justify-end pt-12" onClick={() => setSelected(null)}>
            <div
              className="bg-m3-surface-container-low rounded-t-[24px] border-t border-m3-outline-variant/30 flex flex-col max-h-[100%] shadow-[0_-10px_40px_rgba(0,0,0,0.5)] overflow-hidden mt-auto"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="flex justify-center p-3 shrink-0 cursor-grab active:cursor-grabbing">
                <div className="w-12 h-1.5 rounded-full bg-m3-outline-variant/60" />
              </div>
              
              <div className="overflow-y-auto flex-1 min-h-[0] pb-6 overscroll-contain">
                <div className="px-5 pb-5 pt-1 flex items-start gap-4">
                  <div className={`w-12 h-12 rounded-full flex items-center justify-center text-lg font-medium shrink-0 shadow-sm ${c.bg} ${c.fg}`}>
                    {initials(t.counterparty)}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="text-lg leading-tight font-medium tracking-tight text-m3-on-surface break-words">
                      {t.counterparty}
                    </div>
                    <div className="text-xs text-m3-on-surface-variant mt-1.5 font-medium">
                      {t.date.split('-').reverse().join(' ')} · {t.time}
                    </div>
                  </div>
                  <div className={`text-xl font-medium font-mono shrink-0 ${t.type === 'credit' ? 'text-m3-pos' : 'text-m3-on-surface'}`}>
                    {t.type === 'credit' ? '+' : '−'}₹{t.amount.toLocaleString('en-IN')}
                  </div>
                </div>

                <div className="flex flex-wrap gap-2 px-5 pb-5">
                  <div className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium bg-m3-secondary-container text-m3-on-secondary-container shadow-sm border border-m3-secondary-container/50`}>
                    {t.type === 'credit' ? <ArrowUpRight size={14} strokeWidth={2.5} /> : <ArrowDownRight size={14} strokeWidth={2.5} />}
                    {t.type === 'credit' ? 'Credit' : 'Debit'}
                  </div>
                  <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs bg-m3-surface-container text-m3-on-surface border border-m3-outline-variant font-mono shadow-sm">
                    <CreditCard size={14} strokeWidth={2} />
                    {t.account}
                  </div>
                </div>

                <div className="h-px bg-m3-outline-variant/30 mx-5 mb-5" />

                <div className="px-5 mb-5">
                  <div className="text-[10px] tracking-widest text-m3-on-surface-variant mb-2 font-mono ml-1">SLM ENTITY OUTPUT</div>
                  <div className="border border-m3-outline-variant/30 rounded-2xl overflow-hidden bg-m3-bg shadow-inner">
                    {[
                      ['amount', t.amount, 'num'],
                      ['type', `"${t.type}"`, 'str'],
                      ['counterparty', `"${t.counterparty}"`, 'str'],
                      ['date', `"${t.date}"`, 'str'],
                      ['account', `"${t.account}"`, 'str'],
                    ].map(([k, v, kind], i) => (
                      <div key={k} className={`flex items-baseline gap-2 px-4 py-2 ${i !== 0 ? 'border-t border-m3-outline-variant/30' : ''}`}>
                        <span className="font-mono text-xs text-m3-on-surface-variant min-w-[100px] shrink-0">{k}</span>
                        <span className="font-mono text-xs text-m3-on-surface-variant">:</span>
                        <span className={`font-mono text-xs truncate ${kind === 'str' ? 'text-green-400' : 'text-blue-400'}`}>{v}</span>
                      </div>
                    ))}
                  </div>
                </div>

                <div className="px-5">
                  <div className="text-[10px] tracking-widest text-m3-on-surface-variant mb-2 font-mono ml-1">SOURCE SMS RECEIVED</div>
                  <div className="border border-m3-outline-variant/30 rounded-2xl overflow-hidden bg-m3-bg shadow-inner flex flex-col">
                    <div className="px-4 py-2.5 border-b border-m3-outline-variant/30 flex items-center gap-2">
                      <span className="text-[10px] text-m3-on-surface-variant">sender ID</span>
                      <span className="px-2 py-0.5 bg-m3-primary-container text-m3-on-primary-container font-mono text-[10px] rounded-md">
                        {t.sender}
                      </span>
                    </div>
                    <div className="px-4 py-3 font-mono text-xs text-m3-on-surface-variant leading-relaxed">
                      {t.raw}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        );
      })()}

      {/* DETAIL TELEMETRY SHEET FOR IN-PROGRESS SLM PIPELINE UPDATES */}
      <AnimatePresence>
        {selectedProcessing && (
          <div className="absolute inset-x-0 top-0 bottom-[72px] z-50 bg-black/60 backdrop-blur-sm flex flex-col justify-end pt-12" onClick={() => setSelectedProcessing(null)}>
            <motion.div
              initial={{ y: 250, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              exit={{ y: 250, opacity: 0 }}
              className="bg-m3-surface-container-low rounded-t-[28px] border-t border-m3-outline-variant/30 flex flex-col max-h-[88%] shadow-[0_-12px_44px_rgba(0,0,0,0.6)] overflow-hidden mt-auto"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="flex justify-center p-3 shrink-0 cursor-pointer" onClick={() => setSelectedProcessing(null)}>
                <div className="w-12 h-1.5 rounded-full bg-m3-outline-variant/60" />
              </div>

              {/* Drawer Header */}
              <div className="px-5 pb-3 pt-1 flex justify-between items-center border-b border-m3-outline-variant/15 shrink-0">
                <div className="flex items-center gap-1.5">
                  <Cpu size={15} className="text-amber-500 animate-spin" />
                  <span className="text-xs font-bold font-mono text-m3-on-surface uppercase tracking-wide">Live SLM Runtime Engine</span>
                </div>
                <button 
                  onClick={() => setSelectedProcessing(null)}
                  className="text-xs font-bold text-m3-on-surface-variant hover:text-m3-on-surface px-3 py-1 bg-m3-surface-container-high rounded-full border border-m3-outline-variant/30 transition-colors"
                >
                  Close Logs
                </button>
              </div>

              {/* Panel body with high-fidelity telemetry log */}
              <div className="p-5 overflow-y-auto space-y-4">
                {/* Hardware Context */}
                <div className="p-3 bg-m3-bg rounded-xl border border-m3-outline-variant/15 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Binary size={16} className="text-m3-primary" />
                    <div>
                      <h5 className="text-[10px] font-bold text-m3-on-surface">Local Device CPU Runtime</h5>
                      <p className="text-[8.5px] text-m3-on-surface-variant font-mono">Qwen-1.7B-Chat-Int4.gguf</p>
                    </div>
                  </div>
                  <div className="text-right">
                    <p className="text-[9px] font-mono text-m3-primary font-bold">28 ms/tok</p>
                    <p className="text-[7.5px] font-mono text-m3-on-surface-variant">36.7 tok/sec</p>
                  </div>
                </div>

                {/* Input text */}
                <div>
                  <h4 className="text-[9px] font-mono tracking-wider uppercase text-m3-on-surface-variant mb-1.5 ml-1">Original raw input string</h4>
                  <div className="p-3 bg-m3-bg border border-m3-outline-variant/20 rounded-xl font-mono text-[11px] text-m3-on-surface-variant leading-relaxed">
                    "{selectedProcessing.body}"
                  </div>
                </div>

                {/* Live Stages Timeline */}
                <div>
                  <h4 className="text-[9px] font-mono tracking-wider uppercase text-m3-on-surface-variant mb-2 ml-1">Compiler progress pipeline</h4>
                  <div className="bg-m3-bg border border-m3-outline-variant/20 rounded-xl p-3.5 space-y-3 font-mono">
                    {selectedProcessing.stages.map((st: any, idx: number) => {
                      const isActive = idx === currentStageIndex;
                      const isDone = idx < (currentStageIndex || 0);
                      
                      return (
                        <div key={st.name} className="flex items-start gap-3">
                          <div className="pt-0.5">
                            {isDone ? (
                              <span className="text-m3-pos font-bold text-[10px]">●</span>
                            ) : isActive ? (
                              <span className="text-amber-500 font-bold text-[10px] animate-ping">▶</span>
                            ) : (
                              <span className="text-m3-on-surface-variant/20 text-[10px]">○</span>
                            )}
                          </div>
                          <div className="flex-1 min-w-0">
                            <div className="flex justify-between items-baseline">
                              <span className={`text-[11px] font-bold ${isActive ? 'text-amber-500' : isDone ? 'text-m3-on-surface' : 'text-m3-on-surface-variant/40'}`}>
                                {st.name}
                              </span>
                              <span className={`text-[8px] uppercase px-1 rounded ${
                                isActive ? 'bg-amber-500/15 text-amber-500 animate-pulse' : isDone ? 'bg-m3-pos-container/20 text-m3-on-pos-container' : 'text-m3-on-surface-variant/20'
                              }`}>
                                {isActive ? 'compiling' : isDone ? 'complete' : 'queued'}
                              </span>
                            </div>
                            <p className="text-[9px] text-m3-on-surface-variant/80 mt-0.5 leading-relaxed">{st.description}</p>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>

                {/* Simulated Attention heat factors */}
                <div>
                  <h4 className="text-[9px] font-mono tracking-wider uppercase text-m3-on-surface-variant mb-2 ml-1">Structured key attention values</h4>
                  <div className="space-y-2">
                    {simulatedAttentionWeights.map((item, i) => {
                      const isStagePassedExtract = (currentStageIndex || 0) >= 2;
                      return (
                        <div key={i} className="p-2.5 bg-m3-bg rounded-xl border border-m3-outline-variant/15 flex items-center justify-between">
                          <div>
                            <span className="text-[8.5px] font-mono font-bold text-m3-primary block uppercase tracking-wider">{item.field}</span>
                            <span className="text-xs font-mono text-m3-on-surface mt-0.5 inline-block bg-m3-surface-container px-1.5 py-0.5 rounded border border-m3-outline-variant/30">
                              {isStagePassedExtract ? item.token : "..."}
                            </span>
                          </div>
                          <div className="text-right font-mono">
                            <span className={`text-[10px] font-bold ${isStagePassedExtract ? 'text-m3-pos' : 'text-m3-on-surface-variant/20'}`}>
                              {isStagePassedExtract ? `conf: ${(item.weight * 100).toFixed(1)}%` : "evaluating"}
                            </span>
                            <div className="w-16 h-1 rounded-full bg-m3-surface-container overflow-hidden mt-1">
                              <div 
                                className="h-full bg-m3-pos transition-all duration-500" 
                                style={{ width: isStagePassedExtract ? `${item.weight * 100}%` : '0%' }} 
                              />
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>

                {/* Secure local warning */}
                <div className="p-3 rounded-2xl bg-m3-surface-container-high border border-m3-outline-variant/30 flex items-start gap-2">
                  <ShieldCheck size={16} className="text-m3-pos shrink-0 mt-0.5" />
                  <div>
                    <h6 className="text-[9.5px] font-bold text-m3-on-surface">Zero Data Left Your Screen</h6>
                    <p className="text-[8px] text-m3-on-surface-variant leading-relaxed mt-0.5">Parameters run natively using llama.cpp within WebAssembly boundaries. Internet permission was not requested nor required.</p>
                  </div>
                </div>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </>
  );
};
