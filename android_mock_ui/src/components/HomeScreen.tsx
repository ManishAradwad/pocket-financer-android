import React, { useState, useEffect } from 'react';
import { Settings, RefreshCw, CheckCircle2, CloudDownload, ChevronRight, ArrowUpRight, ArrowDownRight } from 'lucide-react';
import { PERIODS, fmt, initials, avColor, isVpa } from '../data';

export const HomeScreen = ({ goToTxns }: { goToTxns: () => void }) => {
  const [period, setPeriod] = useState('Day');
  const [syncState, setSyncState] = useState('pending');
  const [progress, setProgress] = useState(0);
  const d = PERIODS[period];

  const handleSync = () => {
    if (syncState !== 'pending') return;
    setSyncState('syncing');
    setProgress(0);
    let p = 0;
    const iv = setInterval(() => {
      p += Math.random() * 18 + 8;
      if (p >= 100) {
        p = 100;
        clearInterval(iv);
        setTimeout(() => setSyncState('done'), 300);
      }
      setProgress(Math.min(p, 100));
    }, 300);
  };

  const eyebrow = period === 'Day' ? 'Today · Fri, 25 Apr' : period === 'Week' ? 'This week · Apr 21–25' : 'This month · April 2026';

  return (
    <div className="flex-1 overflow-y-auto hide-scrollbar bg-m3-surface text-m3-on-surface">
      {/* Header */}
      <div className="px-4 pt-4 pb-2 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-m3-primary/30 to-m3-primary flex items-center justify-center text-m3-on-primary font-bold text-base shadow-sm">
            ₹
          </div>
          <div className="text-lg font-bold tracking-tight text-m3-on-surface ml-1.5 font-display">Pocket Financer</div>
        </div>
        <button className="w-10 h-10 rounded-full flex items-center justify-center text-m3-on-surface-variant hover:bg-m3-surface-variant transition-colors">
          <Settings size={20} strokeWidth={2} />
        </button>
      </div>

      {/* Hero Zone */}
      <div className="mx-4 mt-2 mb-4 p-4 rounded-[20px] border border-m3-outline-variant/25 bg-m3-surface-container-low overflow-hidden relative">
        <div className="flex items-center gap-2 mb-2">
          <div className="w-1.5 h-1.5 rounded-full bg-m3-pos animate-pulse" />
          <span className="text-xs font-semibold text-m3-on-surface-variant tracking-wide font-display">{eyebrow}</span>
        </div>
        <div className="flex items-baseline gap-1.5 mb-2.5">
          <span className="text-xl font-light text-m3-on-surface-variant/60 leading-none">₹</span>
          <span className="text-4xl font-extrabold text-m3-on-surface tracking-tight leading-none font-display">
            {d.amount.toLocaleString('en-IN')}
          </span>
        </div>
        <div className="flex items-center justify-between">
          <div className="text-xs font-medium text-m3-on-surface-variant">
            {d.txnCount} transactions
          </div>
          <div
            className={`inline-flex items-center gap-1 mt-1 px-2.5 py-1 rounded-full text-xs font-medium ${
              d.delta.dir === 'less'
                ? 'bg-m3-pos-container text-m3-on-pos-container'
                : 'bg-m3-error-container text-m3-on-error-container'
            }`}
          >
            {d.delta.dir === 'less' ? <ArrowDownRight size={14} strokeWidth={2.5} /> : <ArrowUpRight size={14} strokeWidth={2.5} />}
            {d.delta.label}
          </div>
        </div>
      </div>

      {/* Sync Strip */}
      {syncState === 'pending' && (
        <div
          className="mx-4 mb-5 rounded-2xl p-3 flex items-center gap-3 cursor-pointer relative overflow-hidden bg-m3-primary-container text-m3-on-primary-container hover:bg-m3-primary-container/80 transition-colors shadow-sm"
          onClick={handleSync}
        >
          <div className="w-10 h-10 rounded-full bg-m3-on-primary-container/10 flex items-center justify-center shrink-0">
            <CloudDownload size={20} strokeWidth={2} />
          </div>
          <div className="flex-1">
            <div className="text-sm font-medium tracking-tight">New messages to process</div>
            <div className="text-xs opacity-80 mt-0.5">Tap to sync and update balance</div>
          </div>
          <div className="px-2.5 py-0.5 rounded-full bg-m3-on-primary-container text-m3-primary-container text-xs font-bold shrink-0">
            8
          </div>
        </div>
      )}
      {syncState === 'syncing' && (
        <div className="mx-4 mb-5 rounded-2xl p-3 flex items-center gap-3 relative overflow-hidden bg-m3-surface-container-high border border-m3-outline-variant/30">
          <div
            className="absolute left-0 top-0 bottom-0 bg-m3-primary/10 transition-all duration-300 ease-linear pointer-events-none"
            style={{ width: `${progress}%` }}
          />
          <div className="w-10 h-10 rounded-full bg-m3-primary/10 flex items-center justify-center text-m3-primary shrink-0 relative z-10">
            <RefreshCw size={20} className="animate-spin" strokeWidth={2} />
          </div>
          <div className="flex-1 z-10">
            <div className="text-sm font-medium tracking-tight text-m3-primary">Syncing messages...</div>
            <div className="text-xs text-m3-on-surface-variant font-mono mt-0.5">
              {Math.round((progress * 8) / 100)} / 8 processed
            </div>
          </div>
        </div>
      )}
      {syncState === 'done' && (
        <div className="mx-4 mb-5 rounded-2xl p-3 flex items-center gap-3 bg-m3-pos-container text-m3-on-pos-container shadow-sm border border-m3-pos/20">
          <div className="w-10 h-10 rounded-full bg-m3-on-pos-container/10 flex items-center justify-center shrink-0">
            <CheckCircle2 size={20} strokeWidth={2} />
          </div>
          <div className="flex-1">
            <div className="text-sm font-medium tracking-tight">All synced</div>
            <div className="text-xs opacity-80 mt-0.5">Updated just now</div>
          </div>
        </div>
      )}

      {/* Period Switcher (MD3 Segmented Button) */}
      <div className="mx-4 mb-5 flex bg-m3-surface-container-lowest rounded-full p-1 border border-m3-outline-variant/30">
        {Object.entries(PERIODS).map(([lbl, pd]) => (
          <button
            key={lbl}
            className={`flex-1 flex flex-col items-center py-1.5 rounded-full transition-all duration-200 ${
              period === lbl ? 'bg-m3-secondary-container shadow-sm' : 'hover:bg-m3-surface-container-low'
            }`}
            onClick={() => setPeriod(lbl)}
          >
            <span className={`text-xs font-medium tracking-wide ${period === lbl ? 'text-m3-on-secondary-container' : 'text-m3-on-surface-variant'}`}>{lbl}</span>
            <span className={`text-[10px] mt-0.5 ${period === lbl ? 'text-m3-on-secondary-container opacity-80' : 'text-m3-on-surface-variant opacity-60'}`}>
              ₹{pd.amount.toLocaleString('en-IN')}
            </span>
          </button>
        ))}
      </div>

      {/* Recent Transactions Card */}
      <div className="mx-4 mb-6 bg-m3-surface-container-low rounded-[20px] overflow-hidden border border-m3-outline-variant/30">
        <div className="px-4 py-3 flex items-center justify-between border-b border-m3-outline-variant/20">
          <span className="text-sm font-medium text-m3-on-surface">Recent transactions</span>
          <button
            className="text-xs font-medium text-m3-primary hover:bg-m3-primary/10 transition-colors flex items-center gap-0.5 px-2 py-1 rounded-full"
            onClick={goToTxns}
          >
            All <ChevronRight size={14} />
          </button>
        </div>
        <div className="pb-1">
        {d.recent.map((t: any, i: number) => {
          const vpa = isVpa(t.c);
          const c = avColor(t.c || '?');
          return (
            <div
              key={i}
              className={`flex items-center gap-3 px-4 py-3 cursor-pointer hover:bg-m3-surface-container-high transition-colors ${
                i !== 0 ? 'border-t border-m3-outline-variant/20' : ''
              }`}
              onClick={goToTxns}
            >
              <div className={`w-10 h-10 rounded-full flex items-center justify-center text-sm font-medium shrink-0 ${c.bg} ${c.fg}`}>
                {initials(t.c)}
              </div>
              <div className="flex-1 min-w-0">
                <div className="truncate text-sm font-medium text-m3-on-surface">
                  {t.c}
                </div>
                <div className="inline-flex mt-0.5 px-2 py-0.5 rounded text-m3-on-surface-variant font-mono text-[9px] uppercase tracking-wider bg-m3-surface-container-high">
                  {t.ac}
                </div>
              </div>
              <div className="text-right shrink-0">
                <div className={`text-sm font-medium font-mono ${t.t === 'credit' ? 'text-m3-pos' : 'text-m3-on-surface'}`}>
                  {t.t === 'debit' ? '−' : '+'}₹{t.a.toLocaleString('en-IN')}
                </div>
                <div className="text-[10px] text-m3-on-surface-variant mt-0.5">{t.time}</div>
              </div>
            </div>
          );
        })}
        </div>
      </div>
    </div>
  );
};
