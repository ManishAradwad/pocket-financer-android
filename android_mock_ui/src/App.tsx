import React, { useState } from 'react';
import { Home, List, PieChart, Settings } from 'lucide-react';
import { HomeScreen } from './components/HomeScreen';
import { TransactionsScreen } from './components/TransactionsScreen';
import { InsightsScreen } from './components/InsightsScreen';
import { OnboardingFlow } from './components/OnboardingFlow';

export default function App() {
  const [tab, setTab] = useState(0);
  const [showOnboarding, setShowOnboarding] = useState<boolean>(true);

  const handleOnboardingComplete = () => {
    setShowOnboarding(false);
  };

  const goToTxns = () => setTab(1);

  const screens = [
    <HomeScreen goToTxns={goToTxns} />,
    <TransactionsScreen />,
    <InsightsScreen />,
    <div className="flex-1 flex items-center justify-center bg-m3-bg text-m3-on-surface-variant font-medium">
      Settings · coming soon
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

            {/* Bottom Navigation (MD3 Navigation Bar) */}
            <div className="absolute bottom-0 left-0 right-0 h-[72px] bg-m3-surface-container flex items-center justify-around px-2 z-40 pb-2 pt-1">
              {[
                { icon: Home, label: 'Home' },
                { icon: List, label: 'Transactions' },
                { icon: PieChart, label: 'Insights' },
                { icon: Settings, label: 'Settings' },
              ].map((item, i) => {
                const active = tab === i;
                return (
                  <div
                    key={i}
                    className="flex flex-col items-center gap-1 cursor-pointer w-[64px] group"
                    onClick={() => setTab(i)}
                  >
                    <div 
                      className={`w-14 h-8 rounded-full flex items-center justify-center transition-all duration-200 ${
                        active ? 'bg-m3-secondary-container text-m3-on-secondary-container' : 'text-m3-on-surface-variant hover:bg-m3-on-surface-variant/10'
                      }`}
                    >
                      <item.icon size={22} strokeWidth={active ? 2.5 : 2} className={active ? 'fill-m3-on-secondary-container/20' : ''} />
                    </div>
                    <span 
                      className={`text-[11px] font-medium tracking-wide transition-colors duration-200 ${
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
