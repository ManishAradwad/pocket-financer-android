import React, { useState } from 'react';
import { COUNTERPARTIES, TREND, MAX_V, SIX_AVG, fmt, fmtK, isVpa } from '../data';

export const InsightsScreen = () => {
  const [mPeriod, setMPeriod] = useState('Month');
  const ms = COUNTERPARTIES[mPeriod];
  const topMax = ms.find((m) => !m.tail)?.t || 1;

  return (
    <div className="flex-1 overflow-y-auto hide-scrollbar bg-m3-surface text-m3-on-surface pb-[72px]">
      {/* Header */}
      <div className="px-4 pt-4 pb-2 flex items-center justify-between">
        <div className="text-lg font-bold tracking-tight text-m3-on-surface font-display">Insights</div>
      </div>

      {/* Spend Trend */}
      <div className="px-4 mt-2 mb-6">
        <div className="flex items-baseline justify-between mb-2">
          <span className="text-sm font-medium text-m3-on-surface">Spend trend</span>
          <span className="font-mono text-[9px] font-medium tracking-widest text-m3-on-surface-variant uppercase">LAST 6 MONTHS</span>
        </div>
        
        <div className="bg-m3-surface-container-low rounded-[20px] overflow-hidden border border-m3-outline-variant/30 pt-4 px-4 shadow-sm">
          <div className="flex items-end gap-1.5 h-[100px] mb-3">
            {TREND.map((m) => (
              <div key={m.l} className="flex-1 flex flex-col items-center gap-1.5 h-full">
                <span className={`font-mono text-[9px] whitespace-nowrap leading-none ${m.cur ? 'text-m3-primary font-medium' : 'text-m3-on-surface-variant'}`}>
                  {fmtK(m.v)}
                </span>
                <div className="flex-1 flex items-end w-full">
                  <div 
                    className={`w-full rounded-t-sm transition-all duration-700 ease-out ${m.cur ? 'bg-m3-primary' : 'bg-m3-surface-variant'}`} 
                    style={{ height: `${Math.round((m.v / MAX_V) * 100)}%` }} 
                  />
                </div>
                <span className={`text-[10px] pb-1 uppercase font-medium ${m.cur ? 'text-m3-primary' : 'text-m3-on-surface-variant'}`}>
                  {m.l}
                </span>
              </div>
            ))}
          </div>
          
          <div className="flex border-t border-m3-outline-variant/20 -mx-4 bg-m3-surface-container/30">
            <div className="flex-1 p-3 flex flex-col gap-0.5 border-r border-m3-outline-variant/20">
              <span className="font-mono text-[9px] text-m3-on-surface-variant uppercase">6-MO AVG</span>
              <span className="text-sm font-medium text-m3-on-surface font-mono">{fmt(SIX_AVG)}</span>
            </div>
            <div className="flex-1 p-3 flex flex-col gap-0.5 border-r border-m3-outline-variant/20">
              <span className="font-mono text-[9px] text-m3-on-surface-variant uppercase">MTD</span>
              <span className="text-sm font-medium text-m3-on-surface font-mono">{fmt(38412)}</span>
            </div>
            <div className="flex-1 p-3 flex flex-col gap-0.5">
              <span className="font-mono text-[9px] text-m3-on-surface-variant uppercase">ON PACE</span>
              <span className="text-sm font-medium text-m3-primary font-mono">{fmt(52380)}</span>
            </div>
          </div>
        </div>
      </div>

      {/* Top Counterparties */}
      <div className="mx-4">
        <div className="flex items-center justify-between mb-3 px-1">
          <span className="text-sm font-medium text-m3-on-surface">Top counterparties</span>
          <div className="flex gap-1 bg-m3-surface-container-lowest rounded-full p-1 border border-m3-outline-variant/30">
            {['Week', 'Month', 'All'].map((p) => (
              <button
                key={p}
                className={`px-3 py-1 rounded-full text-[10px] font-medium tracking-wide transition-all duration-200 ${
                  mPeriod === p
                    ? 'bg-m3-secondary-container text-m3-on-secondary-container shadow-sm'
                    : 'text-m3-on-surface-variant hover:bg-m3-surface-container-low'
                }`}
                onClick={() => setMPeriod(p)}
              >
                {p}
              </button>
            ))}
          </div>
        </div>

        <div className="bg-m3-surface-container-low rounded-[20px] overflow-hidden border border-m3-outline-variant/30 mb-5 shadow-sm">
          {ms.map((m, i) => {
            if (m.tail) {
              return (
                <div key="tail" className="flex items-center justify-between px-4 py-3 bg-m3-surface-container/30 border-t border-m3-outline-variant/30">
                  <span className="text-xs text-m3-on-surface-variant">+ {m.count} more</span>
                  <span className="text-xs font-medium text-m3-on-surface-variant font-mono">{fmt(m.t)}</span>
                </div>
              );
            }
            const vpa = isVpa(m.n);
            const barPct = (m.t / topMax) * 100;
            const opacityClass = i === 0 ? 'bg-m3-primary' : i === 1 ? 'bg-m3-primary/80' : i === 2 ? 'bg-m3-primary/60' : 'bg-m3-primary/40';
            
            return (
              <div 
                key={m.n} 
                className={`flex items-center gap-2.5 px-4 py-3 hover:bg-m3-surface-container-high cursor-pointer transition-colors ${
                  i !== 0 ? 'border-t border-m3-outline-variant/20' : ''
                }`}
              >
                <div className="font-mono text-xs font-medium text-m3-on-surface-variant min-w-[20px]">
                  {String(i + 1).padStart(2, '0')}
                </div>
                <div className="flex-1 min-w-0 pr-2">
                  <div className="truncate text-sm font-medium text-m3-on-surface">
                    {m.n}
                  </div>
                  <div className="text-[10px] text-m3-on-surface-variant mt-0.5">
                    {m.c} transaction{m.c > 1 ? 's' : ''}
                  </div>
                  <div className="mt-1.5 h-1 bg-m3-surface-container-highest rounded-full overflow-hidden w-full">
                    <div 
                      className={`h-full rounded-full transition-all duration-500 ease-out ${opacityClass}`} 
                      style={{ width: `${barPct}%` }} 
                    />
                  </div>
                </div>
                <div className="text-xs font-medium text-m3-on-surface text-right shrink-0 font-mono flex items-center h-full">
                  {fmt(m.t)}
                </div>
              </div>
            );
          })}
        </div>
      </div>
      
    </div>
  );
};
