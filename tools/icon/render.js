// 앱 아이콘: 전동차 앞모습 + 7호선 표시 (원본 디자인)
const {chromium}=require('playwright');
const fg=`<g>
 <rect x="31" y="24" width="46" height="60" rx="11" fill="#ffffff"/>
 <rect x="31" y="24" width="46" height="60" rx="11" fill="none" stroke="#0d2747" stroke-width="2.4"/>
 <rect x="36" y="30" width="36" height="22" rx="5" fill="#173e6d"/>
 <path d="M38 33 L50 33 L44 49 L38 49 Z" fill="#ffffff" opacity=".18"/>
 <rect x="36" y="58" width="36" height="5" rx="2.5" fill="#747f00"/>
 <circle cx="41.5" cy="72" r="4.2" fill="#ffd34d" stroke="#0d2747" stroke-width="1.6"/>
 <circle cx="66.5" cy="72" r="4.2" fill="#ffd34d" stroke="#0d2747" stroke-width="1.6"/>
 <rect x="48" y="68" width="12" height="8" rx="2" fill="#0d2747"/>
 <rect x="27" y="84" width="54" height="5" rx="2.5" fill="#0d2747"/>
 <circle cx="76" cy="31" r="10" fill="#747f00" stroke="#ffffff" stroke-width="2.6"/>
 <text x="76" y="35.6" text-anchor="middle" font-family="Arial,Helvetica,sans-serif" font-weight="900" font-size="13" fill="#ffffff">7</text>
</g>`;
const fgSvg=s=>`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="${s}" height="${s}">${fg}</svg>`;
const legacySvg=s=>`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="${s}" height="${s}"><rect x="4" y="4" width="100" height="100" rx="24" fill="#173e6d"/><g transform="translate(54 54) scale(1.12) translate(-54 -56)">${fg}</g></svg>`;
(async()=>{
 const b=await chromium.launch();const p=await b.newPage();
 const shot=async(svg,s,out)=>{await p.setViewportSize({width:s,height:s});await p.setContent(`<html><body style="margin:0;background:transparent">${svg}</body></html>`);await (await p.$('svg')).screenshot({path:out,omitBackground:true});};
 const res='android/app/src/main/res/';
 await shot(fgSvg(432),432,res+'drawable-nodpi/ic_launcher_fg.png');
 for(const [d,s] of [['mdpi',48],['hdpi',72],['xhdpi',96],['xxhdpi',144],['xxxhdpi',192]]) await shot(legacySvg(s),s,res+`mipmap-${d}/ic_launcher.png`);
 await shot(legacySvg(512),512,'tools/icon/preview.png');
 await b.close();
})();
