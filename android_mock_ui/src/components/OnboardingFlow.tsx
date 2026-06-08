import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { MessageSquare, ShieldCheck, Cpu, ArrowRight, Settings, Info, AlertCircle, Loader2, ArrowDownToLine } from 'lucide-react';

enum OnboardingStep {
  WELCOME = 'welcome',
  PERMISSIONS = 'permissions',
  DOWNLOAD_SLM = 'download_slm',
  SYNCING = 'syncing'
}

interface OnboardingFlowProps {
  onComplete: () => void;
}

export const OnboardingFlow: React.FC<OnboardingFlowProps> = ({ onComplete }) => {
  const [step, setStep] = useState<OnboardingStep>(OnboardingStep.WELCOME);
  const [deniedCount, setDeniedCount] = useState(0);
  const [syncProgress, setSyncProgress] = useState(0);
  const [syncMessage, setSyncMessage] = useState('Initializing local engine...');
  
  const [downloadProgress, setDownloadProgress] = useState(0);
  const [isDownloading, setIsDownloading] = useState(false);
  const [downloadEta, setDownloadEta] = useState('2m 14s remaining');

  // SLM Download simulation logic
  useEffect(() => {
    if (step === OnboardingStep.DOWNLOAD_SLM && isDownloading) {
      const progressInterval = setInterval(() => {
        setDownloadProgress(prev => {
          if (prev >= 100) {
            clearInterval(progressInterval);
            setTimeout(() => setStep(OnboardingStep.SYNCING), 600);
            return 100;
          }
          
          if (prev > 85) setDownloadEta('Just a few seconds...');
          else if (prev > 60) setDownloadEta('0m 45s remaining');
          else if (prev > 30) setDownloadEta('1m 02s remaining');
          else if (prev > 10) setDownloadEta('1m 45s remaining');

          return prev + Math.random() * 2;
        });
      }, 100);

      return () => clearInterval(progressInterval);
    }
  }, [step, isDownloading]);

  // Sync animation logic
  useEffect(() => {
    if (step === OnboardingStep.SYNCING) {
      const messages = [
        'Warming up the local AI...',
        'Checking hardware acceleration...',
        'Scanning recent messages...',
        'Analyzing last 7 days of transactions...',
        'Optimizing local database...',
        'Almost ready...'
      ];
      
      let messageIdx = 0;
      const messageInterval = setInterval(() => {
        messageIdx = (messageIdx + 1) % messages.length;
        setSyncMessage(messages[messageIdx]);
      }, 1500);

      const progressInterval = setInterval(() => {
        setSyncProgress(prev => {
          if (prev >= 100) {
            clearInterval(progressInterval);
            clearInterval(messageInterval);
            setTimeout(onComplete, 500);
            return 100;
          }
          return prev + Math.random() * 5;
        });
      }, 200);

      return () => {
        clearInterval(messageInterval);
        clearInterval(progressInterval);
      };
    }
  }, [step, onComplete]);

  const handleGrantPermission = () => {
    // In a real Android app, this would trigger the system permission dialog.
    // Here we simulate compliance.
    setStep(OnboardingStep.DOWNLOAD_SLM);
  };

  const handleDenyPermission = () => {
    setDeniedCount(prev => prev + 1);
  };

  const renderWelcome = () => (
    <motion.div 
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, x: -20 }}
      className="flex flex-col items-center justify-center min-h-full px-6 py-8 text-center"
    >
      <div className="w-16 h-16 rounded-[20px] bg-gradient-to-tr from-m3-primary/30 to-m3-primary flex items-center justify-center text-m3-on-primary font-bold text-3xl shadow-lg mb-6 border border-m3-primary/20">
        ₹
      </div>
      <h1 className="text-3xl font-bold tracking-tight text-m3-on-surface mb-3.5 leading-tight font-display">
        Your money,<br />your business. Period.
      </h1>
      <p className="text-xs text-m3-on-surface-variant font-medium leading-relaxed mb-6">
        Experience unparalleled financial clarity with privacy-first, on-device AI. No cloud uploads. Total control over your transaction history.
      </p>

      <div className="space-y-3.5 w-full max-w-sm mt-1">
        <div className="flex items-center gap-3.5 text-left p-3 rounded-2xl bg-m3-surface-container-low border border-m3-outline-variant/25">
          <div className="w-10 h-10 rounded-xl bg-m3-surface-container flex items-center justify-center shrink-0 text-m3-primary border border-m3-outline-variant/30">
            <ShieldCheck className="text-m3-primary" size={20} strokeWidth={2} />
          </div>
          <div>
            <h3 className="text-sm font-semibold text-m3-on-surface font-display">Private by Design</h3>
            <p className="text-xs text-m3-on-surface-variant mt-0.5 leading-snug">Your messages never leave this device.</p>
          </div>
        </div>
        <div className="flex items-center gap-3.5 text-left p-3 rounded-2xl bg-m3-surface-container-low border border-m3-outline-variant/25">
          <div className="w-10 h-10 rounded-xl bg-m3-surface-container flex items-center justify-center shrink-0 text-m3-primary border border-m3-outline-variant/30">
            <Cpu className="text-m3-primary" size={20} strokeWidth={2} />
          </div>
          <div>
            <h3 className="text-sm font-semibold text-m3-on-surface font-display">Local SLM Engine</h3>
            <p className="text-xs text-m3-on-surface-variant mt-0.5 leading-snug">Small Language Model parses SMS locally.</p>
          </div>
        </div>
      </div>

      <button
        onClick={() => setStep(OnboardingStep.PERMISSIONS)}
        className="mt-12 w-full py-3.5 bg-m3-primary text-m3-on-primary font-medium tracking-wide rounded-full flex items-center justify-center gap-2 hover:bg-m3-primary/90 active:scale-[0.98] transition-all"
      >
        Get Started <ArrowRight size={18} strokeWidth={2.5} />
      </button>
    </motion.div>
  );

  const renderPermissions = () => (
    <motion.div 
      initial={{ opacity: 0, x: 20 }}
      animate={{ opacity: 1, x: 0 }}
      exit={{ opacity: 0, x: -20 }}
      className="flex flex-col min-h-full px-6 pt-6 pb-6"
    >
      <div className="w-14 h-14 rounded-2xl bg-m3-surface-container-high flex items-center justify-center text-m3-primary border border-m3-outline-variant/40 mb-5 shrink-0">
        <MessageSquare size={24} strokeWidth={2} />
      </div>
      
      <h2 className="text-2.5xl leading-tight font-bold tracking-tight text-m3-on-surface mb-2 font-display">
        Grant SMS Access
      </h2>
      
      <p className="text-xs font-medium text-m3-on-surface-variant leading-relaxed mb-5">
        Pocket Financer works by parsing your bank's transactional SMS messages. 
      </p>
      
      <div className="bg-m3-surface-container-low p-4 rounded-[20px] border border-m3-outline-variant/20 mb-4 shrink-0">
        <div className="flex gap-3 items-start">
          <Info className="text-m3-primary shrink-0 mt-0.5" size={18} strokeWidth={2} />
          <p className="text-xs text-m3-on-surface-variant leading-relaxed">
            Our local SLM will extract values like amount and counterparty. <strong className="text-m3-on-surface font-semibold">No personal chats are ever read or processed.</strong>
          </p>
        </div>
      </div>

      <div className="bg-m3-pos-container/20 text-m3-on-pos-container border border-m3-pos/20 p-4 rounded-[20px] mb-6 shrink-0">
        <div className="flex gap-3 items-start">
          <ShieldCheck className="text-m3-pos shrink-0 mt-0.5" size={18} strokeWidth={2.5} />
          <p className="text-xs font-medium tracking-wide">
            100% On-Device. <span className="text-m3-pos font-bold">No data ever leaves your device.</span> We do not use cloud servers or trackers.
          </p>
        </div>
      </div>

      {deniedCount > 0 && (
        <motion.div 
          initial={{ opacity: 0, scale: 0.9 }}
          animate={{ opacity: 1, scale: 1 }}
          className="bg-m3-error-container border border-m3-error/20 p-4 rounded-3xl mb-6 shadow-sm"
        >
          <div className="flex gap-3 items-start">
            <AlertCircle className="text-m3-error shrink-0 mt-0.5" size={18} />
            <div className="space-y-1.5">
              <p className="text-xs font-medium text-m3-on-error-container">Permission Required</p>
              <p className="text-[11px] opacity-80 text-m3-on-error-container/90">The app cannot function without SMS access. Please allow it to proceed.</p>
              <button 
                className="text-[10px] uppercase font-bold text-m3-error flex items-center gap-1 mt-1.5"
                onClick={() => {}} // Simulate opening settings
              >
                <Settings size={12} /> OS Settings
              </button>
            </div>
          </div>
        </motion.div>
      )}

      <div className="flex flex-col gap-2">
        <button
          onClick={handleGrantPermission}
          className="w-full py-3.5 bg-m3-primary text-m3-on-primary font-medium rounded-full flex items-center justify-center gap-2 hover:bg-m3-primary/90 active:scale-[0.98] transition-all shadow-sm"
        >
          Allow SMS Access
        </button>
        <button
          onClick={handleDenyPermission}
          className="w-full py-3 text-m3-on-surface-variant font-medium rounded-full hover:bg-m3-surface-container transition-colors"
        >
          Not Now
        </button>
      </div>
    </motion.div>
  );

  const renderDownloadSlm = () => (
    <motion.div 
      initial={{ opacity: 0, x: 20 }}
      animate={{ opacity: 1, x: 0 }}
      exit={{ opacity: 0, x: -20 }}
      className="flex flex-col min-h-full px-6 pt-6 pb-6"
    >
      <div className="w-14 h-14 rounded-2xl bg-m3-surface-container-high flex items-center justify-center text-m3-primary border border-m3-outline-variant/40 mb-5 shrink-0 relative overflow-hidden">
        <Cpu size={24} strokeWidth={2} />
      </div>
      
      <h2 className="text-2.5xl leading-tight font-bold tracking-tight text-m3-on-surface mb-2 font-display">
        Build Your Private Engine
      </h2>
      
      <p className="text-xs font-medium text-m3-on-surface-variant leading-relaxed mb-5">
        We need to download the local Small Language Model (SLM) that powers Pocket Financer.
      </p>

      {/* Model Spec Card */}
      <div className="bg-m3-surface-container rounded-[20px] border border-m3-outline-variant/35 p-4 mb-4 shrink-0">
        <div className="flex justify-between items-center mb-3">
          <span className="text-[10px] font-bold uppercase tracking-widest text-m3-primary font-display">Model Specs</span>
          <span className="text-[10px] font-medium bg-m3-surface-container-high border border-m3-outline-variant/30 px-2 py-0.5 rounded-full text-m3-on-surface-variant font-mono">llama.cpp</span>
        </div>
        <div className="flex items-baseline gap-2 mb-1.5">
          <h3 className="text-xl font-bold tracking-tight text-m3-on-surface leading-none font-display">Qwen 3</h3>
          <span className="text-xs text-m3-on-surface-variant font-medium">1.7B Params</span>
        </div>
        <p className="text-xs text-m3-on-surface-variant leading-relaxed">Highly optimized for on-device reasoning and transaction extraction.</p>
      </div>

      {/* Animated Visual: Extraction Process */}
      <div className="bg-m3-surface-container-low rounded-[20px] p-4 py-5 mb-5 flex flex-col items-center justify-center border border-m3-outline-variant/35 relative overflow-hidden shrink-0 min-h-[160px]">
        <div className="absolute inset-0 opacity-[0.03] bg-[radial-gradient(#fff_1px,transparent_1px)] [background-size:16px_16px]" />
        
        <h3 className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant mb-4 relative z-10 w-full text-center font-display">Extraction Engine</h3>
        
        <div className="relative w-full h-24 max-w-[240px] z-10">
          <AnimatePresence>
            <motion.div
              key="sms"
              initial={{ opacity: 0, y: 10, scale: 0.95 }}
              animate={{ opacity: [0, 1, 1, 0, 0, 0], y: [10, 0, 0, -10, -10, -10], scale: [0.95, 1, 1, 0.95, 0.95, 0.95] }}
              transition={{ duration: 8, repeat: Infinity, times: [0, 0.1, 0.4, 0.5, 0.9, 1], ease: "easeInOut" }}
              className="absolute inset-0 flex items-center justify-center pointer-events-none"
            >
              <div className="bg-m3-surface-container-high p-3 rounded-xl rounded-bl-sm shadow-sm border border-m3-outline-variant/30 w-full text-[10px] font-mono text-m3-on-surface-variant leading-relaxed text-left">
                Dear cust, INR <span className="text-m3-error font-medium">450.00</span> spent on <span className="text-m3-on-surface font-medium">ZOMATO PVT LTD</span> via Card **1234 on 14-Oct.
              </div>
            </motion.div>

            <motion.div
              key="extract"
              initial={{ opacity: 0, y: 10, scale: 0.95 }}
              animate={{ opacity: [0, 0, 0, 1, 1, 0], y: [10, 10, 10, 0, 0, -10], scale: [0.95, 0.95, 0.95, 1, 1, 0.95] }}
              transition={{ duration: 8, repeat: Infinity, times: [0, 0.4, 0.5, 0.6, 0.9, 1], ease: "easeInOut" }}
              className="absolute inset-0 flex items-center justify-center pointer-events-none"
            >
              <div className="bg-m3-surface shadow-sm border border-m3-outline-variant/30 p-2.5 rounded-xl w-full flex flex-col gap-1 font-mono text-[9px] text-left">
                <div className="flex justify-between items-center bg-m3-error/5 px-2 py-0.5 rounded">
                  <span className="text-m3-on-surface-variant/70">amount:</span>
                  <span className="text-m3-error font-medium">450.00</span>
                </div>
                <div className="flex justify-between items-center bg-m3-surface-container px-2 py-0.5 rounded">
                  <span className="text-m3-on-surface-variant/70">counterparty:</span>
                  <span className="text-m3-on-surface font-medium">Zomato</span>
                </div>
                <div className="flex justify-between items-center bg-m3-surface-container px-2 py-0.5 rounded">
                  <span className="text-m3-on-surface-variant/70">type:</span>
                  <span className="text-m3-on-surface font-medium">debit</span>
                </div>
                <div className="flex justify-between items-center bg-m3-surface-container px-2 py-0.5 rounded">
                  <span className="text-m3-on-surface-variant/70">user_account:</span>
                  <span className="text-m3-on-surface font-medium">Card **1234</span>
                </div>
              </div>
            </motion.div>
          </AnimatePresence>
        </div>
      </div>

      {!isDownloading ? (
        <div className="flex flex-col gap-2.5 mt-auto pt-3 shrink-0">
          <p className="text-[10px] text-center text-m3-on-surface-variant font-medium px-4">
            Model size: ~1.2GB. This one-time download ensures your data never leaves this device.
          </p>
          <button
            onClick={() => setIsDownloading(true)}
            className="w-full py-3 bg-m3-primary text-m3-on-primary font-bold font-display rounded-full flex items-center justify-center gap-2 hover:bg-m3-primary/95 active:scale-[0.98] transition-all shadow-sm"
          >
            <ArrowDownToLine size={18} /> Download
          </button>
        </div>
      ) : (
        <div className="flex flex-col mt-auto pt-3 shrink-0">
          <div className="flex justify-between items-end mb-2">
            <span className="text-xs font-semibold text-m3-on-surface font-display">Downloading model...</span>
            <span className="text-[9px] font-bold text-m3-primary uppercase tracking-wider">{downloadEta}</span>
          </div>
          
          <div className="w-full h-2.5 bg-m3-surface-container-highest rounded-full overflow-hidden mb-2 shadow-inner">
            <motion.div 
              className="h-full bg-m3-primary rounded-full"
              animate={{ width: `${downloadProgress}%` }}
              transition={{ ease: "linear" }}
            />
          </div>
          <div className="flex justify-between items-center text-[10px] text-m3-on-surface-variant font-semibold">
            <span>{Math.round(downloadProgress)}%</span>
            <span>1.2 GB</span>
          </div>
        </div>
      )}
    </motion.div>
  );

  const renderSyncing = () => (
    <motion.div 
      initial={{ opacity: 0, scale: 0.9 }}
      animate={{ opacity: 1, scale: 1 }}
      className="flex flex-col items-center justify-center min-h-full px-6 py-8 text-center"
    >
      <div className="relative w-36 h-36 mb-6">
        {/* Animated Rings */}
        <div className="absolute inset-0 rounded-full border-[4px] border-m3-primary/10" />
        <motion.div 
          className="absolute inset-0 rounded-full border-[4px] border-t-m3-primary border-r-transparent border-b-transparent border-l-transparent"
          animate={{ rotate: 360 }}
          transition={{ duration: 1.5, repeat: Infinity, ease: "linear" }}
        />
        
        {/* Progress Fill */}
        <div className="absolute inset-3 rounded-full bg-m3-surface-container flex flex-col items-center justify-center">
          <span className="text-3xl font-bold text-m3-primary tracking-tight leading-none font-display">{Math.round(syncProgress)}<span className="text-lg">%</span></span>
          <span className="text-[9px] font-bold text-m3-on-surface-variant uppercase tracking-widest mt-1">Syncing</span>
        </div>
      </div>

      <div className="h-6 overflow-hidden mb-4">
        <AnimatePresence mode="wait">
          <motion.p 
            key={syncMessage}
            initial={{ y: 20, opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            exit={{ y: -20, opacity: 0 }}
            className="text-xs font-semibold tracking-tight text-m3-on-surface font-display"
          >
            {syncMessage}
          </motion.p>
        </AnimatePresence>
      </div>
      
      <p className="text-[11px] text-m3-on-surface-variant max-w-[200px] leading-relaxed">
        Performing initial sync of the last 7 days. This will only take a moment.
      </p>

      <div className="mt-6 flex items-center justify-center gap-1.5 text-m3-pos bg-m3-pos-container/20 px-3.5 py-1.5 rounded-full border border-m3-pos/20">
        <ShieldCheck size={14} strokeWidth={2.5} />
        <span className="text-[9px] font-bold uppercase tracking-wider">Data never leaves device</span>
      </div>

      {/* Progress Bar Background */}
      <div className="mt-8 w-full h-1 bg-m3-surface-container-highest rounded-full overflow-hidden mb-4">
        <motion.div 
          className="h-full bg-m3-primary"
          animate={{ width: `${syncProgress}%` }}
        />
      </div>
    </motion.div>
  );

  return (
    <div className="absolute inset-0 z-[100] bg-m3-bg overflow-hidden flex flex-col">
      {/* Mock Status Bar */}
      <div className="h-[30px] w-full flex justify-between items-center px-6 text-[11px] font-bold text-m3-on-surface shrink-0">
        <span>9:41</span>
        <div className="flex gap-1.5 items-center opacity-80 text-[10px] tracking-widest text-m3-on-surface-variant">
          ▲▼ ))) ▮▮▮
        </div>
      </div>

      <div className="flex-1 overflow-y-auto hide-scrollbar">
        <AnimatePresence mode="wait">
          {step === OnboardingStep.WELCOME && renderWelcome()}
          {step === OnboardingStep.PERMISSIONS && renderPermissions()}
          {step === OnboardingStep.DOWNLOAD_SLM && renderDownloadSlm()}
          {step === OnboardingStep.SYNCING && renderSyncing()}
        </AnimatePresence>
      </div>
    </div>
  );
};
