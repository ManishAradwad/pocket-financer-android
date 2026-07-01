import React, { useState } from 'react';
import { Shield, RefreshCw, Cpu, Database, HardDrive, Smartphone, ChevronRight, Terminal, Settings2 } from 'lucide-react';

interface SettingsScreenProps {
  autoSync: boolean;
  setAutoSync: (val: boolean) => void;
  developerLogs: boolean;
  setDeveloperLogs: (val: boolean) => void;
  onFactoryReset: () => void;
  onOpenLogs: () => void;
}

export function SettingsScreen({
  autoSync,
  setAutoSync,
  developerLogs,
  setDeveloperLogs,
  onFactoryReset,
  onOpenLogs,
}: SettingsScreenProps) {
  const [selectedModel, setSelectedModel] = useState('qwen-1.7b');

  return (
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
        {/* Device Hardware Section */}
        <div className="bg-m3-surface-container-low rounded-[20px] p-4 border border-m3-outline-variant/20">
          <h4 className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant font-display mb-3">Device Hardware</h4>
          
          <div className="space-y-3">
            <div className="flex justify-between items-center pb-2 border-b border-m3-outline-variant/10">
              <div className="flex items-center gap-2">
                <Database size={14} className="text-m3-on-surface-variant" />
                <span className="text-xs font-semibold text-m3-on-surface">RAM</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="text-xs font-mono text-m3-on-surface-variant">7.6 GB</span>
                <span className="bg-green-500/10 text-green-500 px-2 py-0.5 rounded text-[10px] font-bold">Tier 1</span>
              </div>
            </div>
            
            <div className="flex justify-between items-center pb-2 border-b border-m3-outline-variant/10">
              <div className="flex items-center gap-2">
                <HardDrive size={14} className="text-m3-on-surface-variant" />
                <span className="text-xs font-semibold text-m3-on-surface">GPU</span>
              </div>
              <span className="text-xs font-mono text-m3-on-surface-variant">Adreno (TM) 730</span>
            </div>
            
            <div className="flex justify-between items-center">
              <div className="flex items-center gap-2">
                <Cpu size={14} className="text-m3-on-surface-variant" />
                <span className="text-xs font-semibold text-m3-on-surface">CPU</span>
              </div>
              <span className="text-xs font-mono text-m3-on-surface-variant">8 cores (i8mm ✓)</span>
            </div>
          </div>
        </div>

        {/* Active Model Section */}
        <div className="bg-m3-surface-container-low rounded-[20px] p-4 border border-m3-outline-variant/20">
          <h4 className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant font-display mb-3">Active Model</h4>
          
          <div className="flex gap-2 mb-3">
            <button 
              onClick={() => setSelectedModel('qwen-1.7b')}
              className={`flex-1 py-2 rounded-xl text-xs font-semibold transition-colors border ${selectedModel === 'qwen-1.7b' ? 'bg-m3-primary-container text-m3-on-primary-container border-transparent' : 'bg-transparent text-m3-on-surface border-m3-outline-variant/30 hover:bg-m3-surface-container'}`}
            >
              Qwen 1.7B Int4
            </button>
            <button 
              onClick={() => setSelectedModel('llama-3-8b')}
              className={`flex-1 py-2 rounded-xl text-xs font-semibold transition-colors border ${selectedModel === 'llama-3-8b' ? 'bg-m3-primary-container text-m3-on-primary-container border-transparent' : 'bg-transparent text-m3-on-surface border-m3-outline-variant/30 hover:bg-m3-surface-container'}`}
            >
              Llama 3 8B
            </button>
          </div>
          <p className="text-[10px] text-m3-on-surface-variant leading-relaxed">
            {selectedModel === 'qwen-1.7b' 
              ? 'Ultra-fast optimized model. 3x faster extraction but might miss complex variations. Uses 1.2GB RAM.'
              : 'Highly accurate but slower extraction. Requires Tier 1 device. Uses 4.5GB RAM.'}
          </p>
        </div>

        {/* Background SMS Processing */}
        <div className="bg-m3-surface-container-low rounded-[20px] p-4 border border-m3-outline-variant/20">
          <h4 className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant font-display mb-3">Background Extraction</h4>
          
          <div className="flex justify-between items-center py-1">
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
        </div>

        {/* Engine Status & Test */}
        <div className="bg-m3-surface-container-low rounded-[20px] p-4 border border-m3-outline-variant/20">
          <h4 className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant font-display mb-3">Engine Status & Data</h4>
          
          <div className="flex justify-between items-center mb-4">
            <span className="text-xs font-semibold text-m3-on-surface">Status</span>
            <span className="text-xs font-bold text-green-500 bg-green-500/10 px-2 py-0.5 rounded-full">IDLE</span>
          </div>

          <button 
            type="button"
            onClick={onFactoryReset}
            className="w-full py-2.5 bg-m3-surface-container hover:bg-m3-surface-container-high text-xs font-semibold rounded-xl border border-m3-error/30 text-m3-error flex items-center justify-center gap-1.5 transition-colors"
          >
            <RefreshCw size={14} /> Clear Local Database
          </button>
        </div>

        {/* Developer Options */}
        <div className="bg-m3-surface-container-low rounded-[20px] p-4 border border-m3-outline-variant/20">
          <h4 className="text-[10px] font-bold uppercase tracking-wider text-m3-on-surface-variant font-display mb-3 flex items-center gap-1.5">
            <Settings2 size={12} /> Developer Options
          </h4>
          
          <div className="flex justify-between items-center py-2.5 border-b border-m3-outline-variant/10">
            <div>
              <div className="text-xs font-semibold text-m3-on-surface">Enable Offline Dev Logs</div>
              <div className="text-[10px] text-m3-on-surface-variant mt-0.5">Save compiler raw logs to on-disk buffer</div>
            </div>
            <input 
              type="checkbox" 
              checked={developerLogs}
              onChange={(e) => setDeveloperLogs(e.target.checked)}
              className="w-8 h-4 rounded-full bg-m3-surface-container-highest border-none focus:ring-0 accent-m3-primary cursor-pointer"
            />
          </div>

          <button 
            onClick={onOpenLogs}
            className="w-full mt-3 py-2.5 bg-m3-surface-container hover:bg-m3-surface-container-high text-xs font-semibold rounded-xl border border-m3-outline-variant/40 flex items-center justify-between px-3 transition-colors text-m3-on-surface"
          >
            <div className="flex items-center gap-2">
              <Terminal size={14} /> View Telemetry Logs
            </div>
            <ChevronRight size={14} className="text-m3-on-surface-variant" />
          </button>
        </div>

        {/* Info label */}
        <div className="flex items-center justify-center gap-1 text-[10px] text-m3-on-surface-variant/70 text-center font-mono py-4">
          <Smartphone size={11} /> pocketFinancer v0.8.3-A (beta-local)
        </div>
      </div>
    </div>
  );
}
