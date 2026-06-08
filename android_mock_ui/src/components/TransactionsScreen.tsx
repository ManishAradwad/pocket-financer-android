import React, { useState } from 'react';
import { Search, AlignLeft, ArrowUpRight, ArrowDownRight, CreditCard, ChevronLeft } from 'lucide-react';
import { TXNS, initials, avColor, isVpa } from '../data';

export const TransactionsScreen = () => {
  const [segment, setSegment] = useState('All');
  const [selected, setSelected] = useState<any>(null);

  const filtered = TXNS.filter(
    (t) => segment === 'All' || (segment === 'Debits' && t.type === 'debit') || (segment === 'Credits' && t.type === 'credit')
  );
  const groups = [
    { date: '22-04-2026', label: 'Today · Fri, 25 Apr', today: true, txns: filtered.filter((t) => t.date === '22-04-2026') },
    { date: '21-04-2026', label: 'Mon, 21 Apr', today: false, txns: filtered.filter((t) => t.date === '21-04-2026') },
  ].filter((g) => g.txns.length > 0);

  const monthTotal = filtered.filter((t) => t.type === 'debit').reduce((s, t) => s + t.amount, 0);

  return (
    <>
      <div className="flex-1 overflow-y-auto hide-scrollbar bg-m3-surface text-m3-on-surface">
        {/* Header */}
        <div className="px-4 pt-4 pb-2 flex items-center justify-between">
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
        <div className="mx-4 my-2 p-1 bg-m3-surface-container-lowest flex rounded-full border border-m3-outline-variant/30">
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

        {/* Month Header */}
        <div className="px-4 py-3 flex items-center justify-between border-b border-m3-outline-variant/20">
          <span className="text-sm font-medium text-m3-on-surface-variant">April 2026</span>
          <span className="text-xs font-medium text-m3-on-surface-variant flex items-center gap-1 font-mono">
            OUT <span className="text-m3-primary text-sm">₹</span>
            <span className="text-sm">{monthTotal.toLocaleString('en-IN')}</span>
          </span>
        </div>

        {/* Groups */}
        {groups.map((g) => {
          const dd = g.txns.filter((t) => t.type === 'debit').reduce((s, t) => s + t.amount, 0);
          const dc = g.txns.filter((t) => t.type === 'credit').reduce((s, t) => s + t.amount, 0);
          return (
            <div key={g.date}>
              <div className="px-4 py-2 flex items-center justify-between border-b border-m3-outline-variant/20 bg-m3-surface/95 sticky top-0 backdrop-blur-md z-10">
                <span className={`text-[10px] font-medium tracking-wider uppercase ${g.today ? 'text-m3-primary' : 'text-m3-on-surface-variant'}`}>{g.label}</span>
                <div className="flex items-center gap-1.5">
                  {dd > 0 && (
                    <span className="px-2 py-0.5 rounded-md bg-m3-error-container text-m3-on-error-container text-[10px] font-mono">
                      ↓ ₹{dd.toLocaleString('en-IN')}
                    </span>
                  )}
                  {dc > 0 && (
                    <span className="px-2 py-0.5 rounded-md bg-m3-pos-container text-m3-on-pos-container text-[10px] font-mono">
                      ↑ ₹{dc.toLocaleString('en-IN')}
                    </span>
                  )}
                </div>
              </div>
              {g.txns.map((t) => {
                const vpa = isVpa(t.counterparty);
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
                      <div className="truncate text-sm font-medium text-m3-on-surface">
                        {t.counterparty}
                      </div>
                      <div className="inline-flex mt-0.5 px-2 py-0.5 rounded text-m3-on-surface-variant font-mono text-[9px] uppercase tracking-wider bg-m3-surface-container-high">
                        {t.account}
                      </div>
                    </div>
                    <div className="text-right shrink-0">
                      <div className={`text-sm font-medium font-mono ${t.type === 'credit' ? 'text-m3-pos' : 'text-m3-on-surface'}`}>
                        {t.type === 'debit' ? '−' : '+'}₹{t.amount.toLocaleString('en-IN')}
                      </div>
                      <div className="text-[10px] text-m3-on-surface-variant mt-0.5">{t.time}</div>
                    </div>
                  </div>
                );
              })}
            </div>
          );
        })}
        <div className="h-6" />
      </div>

      {/* Detail Sheet */}
      {selected && (() => {
        const t = selected;
        const vpa = isVpa(t.counterparty);
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
                <div className="text-[10px] tracking-widest text-m3-on-surface-variant mb-2 font-mono ml-1">SLM OUTPUT</div>
                <div className="border border-m3-outline-variant/30 rounded-2xl overflow-hidden bg-m3-bg shadow-inner">
                  {[
                    ['amount', t.amount, 'num'],
                    ['type', `"${t.type}"`, 'str'],
                    ['counterparty', `"${t.counterparty}"`, 'str'],
                    ['date', `"${t.date}"`, 'str'],
                    ['account', `"${t.account}"`, 'str'],
                  ].map(([k, v, kind], i) => (
                    <div key={k} className={`flex items-baseline gap-2 px-4 py-2 ${i !== 0 ? 'border-t border-m3-outline-variant/30' : ''}`}>
                      <span className="font-mono text-xs text-m3-on-surface-variant min-w-[80px] shrink-0">{k}</span>
                      <span className="font-mono text-xs text-m3-on-surface-variant">:</span>
                      <span className={`font-mono text-xs truncate ${kind === 'str' ? 'text-green-400' : 'text-blue-400'}`}>{v}</span>
                    </div>
                  ))}
                </div>
              </div>

              <div className="px-5">
                <div className="text-[10px] tracking-widest text-m3-on-surface-variant mb-2 font-mono ml-1">SOURCE SMS</div>
                <div className="border border-m3-outline-variant/30 rounded-2xl overflow-hidden bg-m3-bg shadow-inner flex flex-col">
                  <div className="px-4 py-2.5 border-b border-m3-outline-variant/30 flex items-center gap-2">
                    <span className="text-[10px] text-m3-on-surface-variant">from</span>
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
    </>
  );
};

