import React, { useState } from 'react';
import { 
  Settings, 
  RefreshCw, 
  CheckCircle2, 
  CloudDownload, 
  ChevronRight, 
  ArrowUpRight, 
  ArrowDownRight, 
  ShieldCheck, 
  Cpu, 
  Sparkles,
  Play,
  Terminal,
  HelpCircle,
  Eye
} from 'lucide-react';
import { PERIODS, fmt, initials, avColor, isVpa } from '../data';
import { motion, AnimatePresence } from 'motion/react';

interface HomeScreenProps {
  goToTxns: () => void;
  syncState: 'pending' | 'syncing' | 'done';
  startSmsProcessing: () => void;
  unsyncedSmsList: any[];
  currentSmsIndex: number | null;
  currentStageIndex: number | null;
  onNavigateToTab: (idx: number) => void;
}

export const HomeScreen = ({ 
  goToTxns,
  syncState,
  startSmsProcessing,
  unsyncedSmsList,
  currentSmsIndex,
  currentStageIndex,
  onNavigateToTab
}: HomeScreenProps) => {
  const [period, setPeriod] = useState('Day');
  const [showDrawer, setShowDrawer] = useState(false);
  const d = PERIODS[period];

  const eyebrow = period === 'Day' ? 'Today · Fri, 25 Apr' : period === 'Week' ? 'This week · Apr 21–25' : 'This month · April 2026';

  // Compute pending vs parsed messages
  const pendingCount = unsyncedSmsList.filter(s => s.status === 'pending').length;
  const processedCount = unsyncedSmsList.filter(s => s.status === 'synced' || s.status === 'filtered_out').length;
  const activeSms = currentSmsIndex !== null ? unsyncedSmsList[currentSmsIndex] : null;

  return (
    <div className="flex-1 overflow-y-auto hide-scrollbar bg-m3-surface text-m3-on-surface">
      {/* Header */}
      <div className="px-4 pt-4 pb-2 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-m3-primary/30 to-m3-primary flex items-center justify-center text-m3-on-primary font-bold text-base shadow-sm">
            ₹
          </div>
          <div className="text-lg font-bold tracking-tight text-m3-on-surface ml-1.5 font-display">pocketFinancer</div>
        </div>
        <button 
          onClick={() => onNavigateToTab(4)}
          className="w-10 h-10 rounded-full flex items-center justify-center text-m3-on-surface-variant hover:bg-m3-surface-variant transition-colors"
        >
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

      {/* Dynamic Sync/Processing Strip Container */}
      <div className="mx-4 mb-5">
        {syncState === 'pending' && (
          <div
            className="rounded-[20px] p-3.5 bg-m3-primary-container text-m3-on-primary-container cursor-pointer hover:bg-m3-primary-container/90 transition-all shadow-sm border border-m3-primary/15 relative overflow-hidden"
            onClick={() => {
              startSmsProcessing();
              setShowDrawer(true);
            }}
          >
            <div className="absolute right-0 top-0 w-24 h-24 bg-m3-on-primary-container/5 rounded-full blur-xl pointer-events-none" />
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-full bg-m3-on-primary-container/10 flex items-center justify-center shrink-0 text-m3-primary">
                <CloudDownload size={20} strokeWidth={2.5} />
              </div>
              <div className="flex-1">
                <div className="text-xs font-bold tracking-tight uppercase opacity-80 leading-none mb-1">Incoming Message Stream</div>
                <div className="text-sm font-bold tracking-tight text-m3-on-surface">3 Unsynced SMS Found</div>
                <p className="text-[10px] opacity-75 mt-0.5">Click to run Local Qwen SLM pipeline</p>
              </div>
              <div className="px-2.5 py-1 rounded-full bg-m3-primary text-m3-on-primary text-[10px] font-bold shrink-0 flex items-center gap-1">
                <Play size={10} fill="currentColor" /> Process
              </div>
            </div>
          </div>
        )}

        {syncState === 'syncing' && (
          <div 
            onClick={() => setShowDrawer(true)}
            className="rounded-[20px] p-3.5 bg-m3-surface-container border-2 border-amber-500/25 cursor-pointer relative overflow-hidden shadow-sm hover:border-amber-500/40 transition-colors animate-pulse"
          >
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-full bg-amber-500/10 flex items-center justify-center text-amber-500 shrink-0">
                <Cpu size={20} className="animate-spin" />
              </div>
              <div className="flex-1">
                <div className="flex items-center gap-1.5">
                  <span className="text-[9px] font-bold uppercase tracking-wider text-amber-500 font-mono">Running Local Qwen SLM</span>
                  <span className="w-1.5 h-1.5 rounded-full bg-amber-500 animate-ping" />
                </div>
                <div className="text-xs font-semibold text-m3-on-surface mt-0.5">
                  {activeSms ? `Analyzing ${activeSms.sender}...` : 'Processing SMS stream...'}
                </div>
                <div className="text-[10px] text-m3-on-surface-variant mt-1 font-mono">
                  Message {processedCount + 1} of {unsyncedSmsList.length}
                </div>
              </div>
              <div className="px-2.5 py-1 rounded-full bg-m3-bg hover:bg-m3-surface-container-high border border-m3-outline-variant/30 text-[10px] font-bold text-m3-primary flex items-center gap-1 shrink-0">
                <Eye size={12} /> Inspect
              </div>
            </div>
          </div>
        )}

        {syncState === 'done' && (
          <div 
            onClick={() => setShowDrawer(true)}
            className="rounded-[20px] p-3.5 bg-m3-pos-container text-m3-on-pos-container shadow-sm border border-m3-pos/20 cursor-pointer relative overflow-hidden hover:bg-m3-pos-container/80 transition-colors"
          >
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-full bg-m3-on-pos-container/10 flex items-center justify-center shrink-0 text-m3-pos">
                <CheckCircle2 size={20} strokeWidth={2.5} />
              </div>
              <div className="flex-1">
                <div className="text-[9px] font-bold leading-none uppercase tracking-wider text-m3-pos/90">Extraction Complete</div>
                <div className="text-xs font-bold text-m3-on-surface mt-0.5">All local messages matched</div>
                <div className="text-[10px] opacity-85 mt-1 leading-none">Balances updated offline successfully</div>
              </div>
              <div className="px-2.5 py-1.5 rounded-full bg-white/20 text-white border border-white/10 text-[10px] font-bold shrink-0">
                View Logs
              </div>
            </div>
          </div>
        )}
      </div>

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
          <span className="text-sm font-bold font-display text-m3-on-surface">Recent synced transactions</span>
          <button
            className="text-xs font-semibold text-m3-primary hover:bg-m3-primary/10 transition-colors flex items-center gap-0.5 px-2.5 py-1 rounded-full"
            onClick={goToTxns}
          >
            All <ChevronRight size={14} strokeWidth={2.5} />
          </button>
        </div>
        <div className="pb-1">
        {d.recent.map((t: any, i: number) => {
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
                <div className="truncate text-sm font-semibold text-m3-on-surface">
                  {t.c}
                </div>
                <div className="inline-flex mt-0.5 px-2 py-0.5 rounded text-m3-on-surface-variant font-mono text-[9px] uppercase tracking-wider bg-m3-surface-container-high leading-none">
                  {t.ac}
                </div>
              </div>
              <div className="text-right shrink-0">
                <div className={`text-sm font-bold font-mono ${t.t === 'credit' ? 'text-m3-pos' : 'text-m3-on-surface'}`}>
                  {t.t === 'debit' ? '−' : '+'}₹{t.a.toLocaleString('en-IN')}
                </div>
                <div className="text-[10px] text-m3-on-surface-variant mt-0.5">{t.time}</div>
              </div>
            </div>
          );
        })}
        </div>
      </div>

      {/* Bottom Drawer Overlay: overall extraction tracking & model telemetry */}
      <AnimatePresence>
        {showDrawer && (
          <div className="absolute inset-x-0 top-0 bottom-[72px] z-50 bg-black/60 backdrop-blur-sm flex flex-col justify-end">
            <motion.div 
              initial={{ y: 350, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              exit={{ y: 350, opacity: 0 }}
              className="bg-m3-surface-container rounded-t-[28px] border-t border-m3-outline-variant/30 flex flex-col max-h-[92%] shadow-[0_-12px_44px_rgba(0,0,0,0.6)] overflow-hidden"
            >
              {/* Drag handles styled */}
              <div className="flex justify-center p-3 shrink-0 cursor-pointer" onClick={() => setShowDrawer(false)}>
                <div className="w-12 h-1 rounded-full bg-m3-outline-variant/40" />
              </div>

              {/* Drawer Header */}
              <div className="px-5 pb-2.5 flex justify-between items-center border-b border-m3-outline-variant/15 shrink-0">
                <div className="flex items-center gap-1.5">
                  <Terminal size={14} className="text-m3-primary" />
                  <span className="text-sm font-bold tracking-tight font-display text-m3-on-surface">On-Device Local SLM Monitor</span>
                </div>
                <button 
                  onClick={() => setShowDrawer(false)}
                  className="text-xs font-semibold text-m3-on-surface-variant hover:text-m3-on-surface px-2.5 py-1 bg-m3-surface-container-high rounded-full border border-m3-outline-variant/30 transition-colors"
                >
                  Close
                </button>
              </div>

              {/* Drawer Body content */}
              <div className="p-5 overflow-y-auto space-y-4">
                {/* Visual message queue */}
                <div>
                  <h4 className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant mb-2 font-display">Target Extraction Queue</h4>
                  <div className="space-y-2">
                    {unsyncedSmsList.map((sms, i) => {
                      const isActive = i === currentSmsIndex;
                      const isComplete = sms.status === 'synced';
                      const isFiltered = sms.status === 'filtered_out';
                      
                      return (
                        <div 
                          key={sms.id}
                          className={`p-2.5 rounded-xl border flex items-start gap-2.5 transition-colors ${
                            isActive 
                              ? 'bg-amber-500/5 border-amber-500/30' 
                              : isComplete 
                                ? 'bg-m3-pos-container/20 border-m3-pos/25'
                                : isFiltered
                                  ? 'bg-m3-outline-variant/10 border-m3-outline-variant/20 opacity-60'
                                  : 'bg-m3-surface-container-low border-m3-outline-variant/15'
                          }`}
                        >
                          <div className="pt-0.5">
                            {isComplete ? (
                              <div className="w-4 h-4 rounded-full bg-m3-pos flex items-center justify-center text-white text-[9px] font-bold">✓</div>
                            ) : isFiltered ? (
                              <div className="w-4 h-4 rounded-full bg-m3-outline-variant flex items-center justify-center text-m3-on-surface text-[8px] font-bold">∅</div>
                            ) : isActive ? (
                              <div className="w-4 h-4 rounded-full bg-amber-500/25 flex items-center justify-center text-amber-500 animate-pulse text-[9px] font-bold">⚡</div>
                            ) : (
                              <div className="w-4 h-4 rounded-full bg-m3-surface-container-highest border border-m3-outline-variant/40 text-[9px] text-m3-on-surface-variant flex items-center justify-center font-mono">{i+1}</div>
                            )}
                          </div>

                          <div className="flex-1 min-w-0">
                            <div className="flex justify-between items-baseline">
                              <span className="text-[11px] font-bold text-m3-on-surface">{sms.sender}</span>
                              <span className="text-[9px] font-mono text-m3-on-surface-variant">{sms.time}</span>
                            </div>
                            <p className="text-[10px] text-m3-on-surface-variant font-mono truncate mt-0.5">{sms.body}</p>
                            
                            {/* If active, display multi-stage pipeline track */}
                            {isActive && (
                              <div className="mt-3 bg-m3-surface p-2.5 rounded-lg border border-m3-outline-variant/25 space-y-2">
                                <div className="text-[9px] font-bold uppercase tracking-wider text-m3-primary flex items-center gap-1">
                                  <Sparkles size={10} className="animate-pulse" /> Qwen.SLM pipeline stages:
                                </div>
                                <div className="space-y-1.5 font-mono text-[9px] leading-relaxed">
                                  {sms.stages.map((st: any, idx: number) => {
                                    const isStageActive = idx === currentStageIndex;
                                    const isStageDone = idx < (currentStageIndex || 0);
                                    
                                    return (
                                      <div key={st.name} className="flex justify-between items-center">
                                        <div className="flex items-center gap-1.5">
                                          <span className={isStageActive ? 'text-amber-500 font-semibold' : isStageDone ? 'text-m3-pos' : 'text-m3-on-surface-variant/40'}>
                                            {isStageActive ? '▶' : isStageDone ? '●' : '○'}
                                          </span>
                                          <span className={isStageActive ? 'text-m3-on-surface font-semibold' : isStageDone ? 'text-m3-on-surface-variant' : 'text-m3-on-surface-variant/40'}>
                                            {st.name}
                                          </span>
                                        </div>
                                        <span className={`text-[8.5px] px-1 rounded uppercase ${
                                          isStageActive 
                                            ? 'bg-amber-500/10 text-amber-500 animate-pulse' 
                                            : isStageDone 
                                              ? 'bg-m3-pos-container/20 text-m3-on-pos-container' 
                                              : 'text-m3-on-surface-variant/20'
                                        }`}>
                                          {isStageActive ? 'running' : isStageDone ? 'done' : 'idle'}
                                        </span>
                                      </div>
                                    );
                                  })}
                                </div>
                              </div>
                            )}

                            {isFiltered && (
                              <div className="mt-1 text-[9px] font-mono text-m3-error bg-m3-error-container/10 px-2 py-0.5 rounded inline-block">
                                Spam: promotional voucher. Extractor discarded.
                              </div>
                            )}

                            {isComplete && sms.parsedData && (
                              <div className="mt-1.5 bg-m3-pos-container/10 p-1.5 rounded border border-m3-pos/10 flex justify-between items-center text-[9px] font-mono text-m3-on-pos-container">
                                <span>Extracted: {sms.parsedData.payee}</span>
                                <span className="font-bold font-sans">₹{sms.parsedData.amount}</span>
                              </div>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>

                {/* Redirect deep links to transaction inspection */}
                {syncState === 'syncing' && (
                  <div className="pt-2">
                    <button
                      onClick={() => {
                        setShowDrawer(false);
                        onNavigateToTab(2); // Transactions screen code
                      }}
                      className="w-full py-2.5 bg-m3-primary text-m3-on-primary font-bold font-display text-xs rounded-full flex items-center justify-center gap-1 hover:bg-m3-primary/95 transition-all shadow-sm shrink-0"
                    >
                      Inspect Active SLM Token Logs <ChevronRight size={13} strokeWidth={2.5} />
                    </button>
                    <div className="text-[9px] text-center text-m3-on-surface-variant/70 mt-2 font-mono">
                      Observe compiler attention vectors & parsed JSON in realtime.
                    </div>
                  </div>
                )}
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

    </div>
  );
};
