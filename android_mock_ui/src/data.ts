export const PERIODS: Record<string, any> = {
  Day:   { 
    amount: 1840,  
    txnCount: 4,  
    delta: { dir: 'less', label: '₹312 less than yesterday' },
    recent: [ 
      {c:'zomato@axisbank',ac:'Credit Card XX7788',a:340,t:'debit',time:'20:20'},
      {c:'METRO RAIL CORP',ac:'Debit Card XX4521',a:200,t:'debit',time:'18:15'},
      {c:'bigbasket@ybl',ac:'Credit Card XX7788',a:1120,t:'debit',time:'14:40'} 
    ]
  },
  Week:  { 
    amount: 12480, 
    txnCount: 22, 
    delta: { dir: 'more', label: '₹1,840 more than last week' },
    recent: [ 
      {c:'zomato@axisbank',ac:'Credit Card XX7788',a:340,t:'debit',time:'Today'},
      {c:'RELIANCE RETAIL',ac:'A/c XX6254',a:2240,t:'debit',time:'Sun'},
      {c:'swiggy@icici',ac:'Credit Card XX7788',a:450,t:'debit',time:'Mon'} 
    ]
  },
  Month: { 
    amount: 38412, 
    txnCount: 84, 
    delta: { dir: 'less', label: '₹3,200 less than last month' },
    recent: [ 
      {c:'zomato@axisbank',ac:'Credit Card XX7788',a:340,t:'debit',time:'20:20'},
      {c:'METRO RAIL CORP',ac:'Debit Card XX4521',a:200,t:'debit',time:'18:15'},
      {c:'bigbasket@ybl',ac:'Credit Card XX7788',a:1120,t:'debit',time:'14:40'} 
    ]
  },
};

export const TXNS = [
  { id:1, counterparty:'zomato@axisbank', account:'Credit Card XX7788', amount:340, type:'debit', date:'22-04-2026', time:'20:20', sender:'AX-AXISBK', raw:'Dear Cardholder, INR 340.00 has been debited from your Axis Bank Credit Card XX7788 at zomato@axisbank on 22-04-2026 20:20:34. Available credit limit: INR 48,220.' },
  { id:2, counterparty:'METRO RAIL CORP', account:'Debit Card XX4521', amount:200, type:'debit', date:'22-04-2026', time:'18:15', sender:'VM-HDFCBK', raw:'HDFC Bank: INR 200.00 debited from Debit Card XX4521 at METRO RAIL CORP on 22-04-2026. Avail Bal: INR 42,810.55.' },
  { id:3, counterparty:'bigbasket@ybl', account:'Credit Card XX7788', amount:1120, type:'debit', date:'22-04-2026', time:'14:40', sender:'AX-AXISBK', raw:'Dear Cardholder, INR 1,120.00 has been debited from your Axis Bank Credit Card XX7788 for a UPI transaction to bigbasket@ybl on 22-04-2026.' },
  { id:4, counterparty:'UBER INDIA', account:'A/c XX6254', amount:180, type:'debit', date:'22-04-2026', time:'09:05', sender:'VM-HDFCBK', raw:'HDFC Bank: INR 180.00 debited from A/c XX6254 via UPI to UBER INDIA on 22-04-2026 09:05. UPI Ref: 420981234567.' },
  { id:5, counterparty:'swiggy@icici', account:'Credit Card XX7788', amount:450, type:'debit', date:'21-04-2026', time:'20:00', sender:'AX-AXISBK', raw:'Dear Cardholder, INR 450.00 has been debited from your Axis Bank Credit Card XX7788 for UPI to swiggy@icici on 21-04-2026.' },
  { id:6, counterparty:'MULTIPL FINTECH SOLUTIONS',account:'A/c XX6254', amount:95000, type:'credit', date:'21-04-2026', time:'08:00', sender:'VM-HDFCBK', raw:'HDFC Bank: INR 95,000.00 credited to A/c XX6254 by MULTIPL FINTECH SOLUTIONS on 21-04-2026 08:00. NEFT Ref: N114240001234.' },
  { id:7, counterparty:'amazon@apl', account:'A/c XX6254', amount:1750, type:'debit', date:'21-04-2026', time:'11:30', sender:'VM-HDFCBK', raw:'HDFC Bank: INR 1,750.00 debited from A/c XX6254 via UPI to amazon@apl on 21-04-2026 11:30. UPI Ref: 420987654321.' },
];

export const TREND = [
  {l:'Nov',v:42800},{l:'Dec',v:51200},{l:'Jan',v:39400},{l:'Feb',v:44600},{l:'Mar',v:47900},{l:'Apr',v:38412,cur:true}
];
export const MAX_V = Math.max(...TREND.map(m=>m.v));
export const SIX_AVG = Math.round(TREND.reduce((s,m)=>s+m.v,0)/TREND.length);

export const COUNTERPARTIES: Record<string, any[]> = {
  Week:  [ {n:'bigbasket@ybl',c:3,t:3360},{n:'zomato@axisbank',c:5,t:2840},{n:'UBER INDIA',c:8,t:1440},{n:'swiggy@icici',c:4,t:1260},{n:'RELIANCE RETAIL',c:2,t:1100},{n:'amazon@apl',c:1,t:890},{n:'METRO RAIL CORP',c:4,t:760},{tail:true,count:4,t:1430} ],
  Month: [ {n:'bigbasket@ybl',c:12,t:14200},{n:'zomato@axisbank',c:22,t:11400},{n:'UBER INDIA',c:31,t:5580},{n:'RELIANCE RETAIL',c:8,t:4820},{n:'swiggy@icici',c:18,t:4100},{n:'amazon@apl',c:4,t:3750},{n:'METRO RAIL CORP',c:18,t:3240},{n:'BookMyShow',c:3,t:2040},{tail:true,count:12,t:8282} ],
  All:   [ {n:'bigbasket@ybl',c:44,t:52400},{n:'zomato@axisbank',c:87,t:43800},{n:'UBER INDIA',c:112,t:20160},{n:'RELIANCE RETAIL',c:28,t:17600},{n:'swiggy@icici',c:67,t:15300},{n:'amazon@apl',c:14,t:13900},{n:'METRO RAIL CORP',c:74,t:12400},{n:'BookMyShow',c:9,t:7600},{tail:true,count:38,t:41000} ],
};

export const fmt = (n: number) => '₹' + n.toLocaleString('en-IN');
export const fmtK = (n: number) => n >= 1000 ? `₹${(n/1000).toFixed(1)}K` : `₹${n}`;
export const isVpa = (m: string) => !!m && m.includes('@');

const AV_COLORS = [
  { bg: 'bg-amber-900', fg: 'text-amber-300' },
  { bg: 'bg-emerald-900', fg: 'text-emerald-300' },
  { bg: 'bg-blue-900', fg: 'text-blue-300' },
  { bg: 'bg-purple-900', fg: 'text-purple-300' },
  { bg: 'bg-rose-900', fg: 'text-rose-300' },
];

export const avColor = (n: string) => AV_COLORS[(n.charCodeAt(0) || 0) % AV_COLORS.length];
export const initials = (n: string) => {
  if (!n) return '?';
  if (n.includes('@')) return n[0].toUpperCase();
  const p = n.trim().split(/\s+/);
  return p.length > 1 ? (p[0][0] + p[1][0]).toUpperCase() : n.slice(0, 2).toUpperCase();
};
