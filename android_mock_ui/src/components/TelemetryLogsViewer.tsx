import React from 'react';
import { ArrowLeft, Terminal, Download, Share2 } from 'lucide-react';

interface TelemetryLogsViewerProps {
  onBack: () => void;
}

export function TelemetryLogsViewer({ onBack }: TelemetryLogsViewerProps) {
  const dummyLogs = `[14:23:41.002] INFO: TelemetryService initializing...
[14:23:41.015] DEBUG: Loading config from preferences
[14:23:41.045] INFO: Qwen 1.7B engine ready. RAM allocated: 1.2GB
[14:23:42.110] DEBUG: Engine test passed in 402ms
[14:25:01.300] INFO: BroadcastReceiver triggered for incoming SMS
[14:25:01.321] DEBUG: SMS non-txn filter running...
[14:25:01.325] INFO: Filter result: PASSED (confidence 0.99)
[14:25:01.350] DEBUG: Token analysis starting on CPU
[14:25:01.810] INFO: Parsing completed in 460ms
[14:25:01.815] DEBUG: Extracted payload: { payee: 'ZEPTOWORLD PL', amount: 1250, type: 'debit' }
[14:25:01.822] INFO: Ledger matcher synced transaction successfully`;

  return (
    <div className="absolute inset-0 z-50 flex flex-col bg-m3-surface text-m3-on-surface transition-transform duration-300">
      <div className="h-14 flex items-center px-2 bg-m3-surface-container shadow-sm border-b border-m3-outline-variant/20 shrink-0">
        <button 
          onClick={onBack}
          className="p-2 rounded-full text-m3-on-surface hover:bg-m3-on-surface/10 transition-colors"
        >
          <ArrowLeft size={24} />
        </button>
        <h2 className="ml-2 text-[15px] font-medium tracking-tight text-m3-on-surface">Telemetry Logs</h2>
        
        <div className="ml-auto flex gap-1 pr-2">
          <button className="p-2 rounded-full text-m3-on-surface hover:bg-m3-on-surface/10 transition-colors">
            <Share2 size={20} />
          </button>
          <button className="p-2 rounded-full text-m3-on-surface hover:bg-m3-on-surface/10 transition-colors">
            <Download size={20} />
          </button>
        </div>
      </div>

      <div className="p-3 bg-m3-surface-container-low shrink-0 border-b border-m3-outline-variant/20 flex gap-2 items-center text-xs">
        <Terminal size={14} className="text-m3-primary" />
        <span className="font-semibold text-m3-on-surface-variant">Live Parser Feed</span>
        <span className="ml-auto text-[10px] bg-green-500/20 text-green-500 px-2 py-0.5 rounded-full font-bold">RECORDING</span>
      </div>

      <div className="flex-1 bg-black p-4 overflow-y-auto">
        <pre className="text-[10px] font-mono leading-relaxed text-green-400 break-words whitespace-pre-wrap">
          {dummyLogs}
        </pre>
        {/* Blinking cursor effect */}
        <div className="inline-block w-1.5 h-3 bg-green-400 animate-pulse mt-2"></div>
      </div>
    </div>
  );
}
