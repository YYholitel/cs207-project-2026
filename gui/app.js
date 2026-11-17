'use strict';
const $ = id => document.getElementById(id);
const ui = {connected:false,snapshot:null,revision:-1,token:null,busy:false,steps:8,started:null,elapsed:0,resetClock:false,lastUpdate:0};
const preview = [2,8,3,1,6,4,7,0,5];
const tiles = new Map();
let toastTimer, pollPending=false;
for(let i=0;i<9;i++){
  const slot=document.createElement('div'); slot.className='slot';
  const label=document.createElement('span');label.textContent=String(i+1).padStart(2,'0');slot.append(label);$('slots').append(slot);
  const key=document.createElement('button');key.type='button';key.textContent=i;key.setAttribute('aria-label',`输入数字 ${i}`);
  key.addEventListener('click',()=>sendCommand(String(i)));$('keypad').append(key);
}
for(let value=1;value<=8;value++){
  const tile=document.createElement('button'); tile.className='tile';tile.type='button';tile.textContent=value;
  tile.setAttribute('aria-label',`移动棋子 ${value}`);
  const corner=document.createElement('span');corner.className='tile-corner';corner.setAttribute('aria-hidden','true');tile.append(corner);
  tile.addEventListener('click',()=>sendCommand(String(value)));$('tile-layer').append(tile);tiles.set(value,tile);
}
for(let i=0;i<8;i++){const dot=document.createElement('span');dot.className='led-dot';dot.dataset.index=i+1;$('led-strip').append(dot);}
for(let i=0;i<5;i++){$('demo-progress').append(document.createElement('i'));}
function toast(text,error=false){clearTimeout(toastTimer);$('toast').textContent=text;$('toast').className=`toast show${error?' error':''}`;toastTimer=setTimeout(()=>$('toast').classList.remove('show'),3200);}
async function api(path,body){
  const options=body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json','X-Session-Token':ui.token||''},body:JSON.stringify(body)};
  const response=await fetch(path,{...options,cache:'no-store'});
  const data=await response.json();if(!response.ok)throw new Error(data.error||'连接暂时不可用');
  if(data.token)ui.token=data.token;return data;
}
function updateClock(snapshot,previous){
  const now=performance.now();
  if(!ui.connected){if(ui.started)ui.elapsed=now-ui.started;ui.started=null;return;}
  if(ui.resetClock || snapshot?.state===0 || snapshot?.flags&1){ui.started=null;ui.elapsed=0;ui.resetClock=false;}
  if(snapshot?.state===1 && !(snapshot.flags&1) && !ui.started)ui.started=now-ui.elapsed;
  if(snapshot?.state===2 && ui.started){ui.elapsed=now-ui.started;ui.started=null;}
}
function celebrate(){
  const card=$('game-card');card.classList.remove('win-glow');void card.offsetWidth;card.classList.add('win-glow');
  if(matchMedia('(prefers-reduced-motion: reduce)').matches)return;
  $('confetti').replaceChildren();
  for(let i=0;i<22;i++){const piece=document.createElement('i');piece.style.left=`${(i*43)%100}%`;piece.style.background=['#b3d9b7','#ff865e','#e5e5da'][i%3];piece.style.animationDelay=`${(i%6)*.055}s`;$('confetti').append(piece);}
  setTimeout(()=>$('confetti').replaceChildren(),2000);
}
function apply(data){
  if(data.revision<ui.revision)return;
  const previous=ui.snapshot, wasConnected=ui.connected;
  Object.assign(ui,{connected:data.connected,snapshot:data.snapshot,revision:data.revision,port:data.port,ports:data.ports||[],error:data.error,lastUpdate:Date.now()});
  updateClock(ui.snapshot,previous);
  if(ui.connected && previous && ui.snapshot){
    if(ui.snapshot.state===2 && previous.state!==2){celebrate();toast('归位了。漂亮的一局！');}
    if(ui.snapshot.bad>previous.bad || ((ui.snapshot.flags&2) && !(previous.flags&2))){const card=$('game-card');card.classList.remove('shake');void card.offsetWidth;card.classList.add('shake');}
  }
  if(wasConnected && !ui.connected)toast(data.error||'已断开开发板连接',Boolean(data.error));
  render();
}
function positionBoard(){
  const snapshot=ui.snapshot, values=snapshot?snapshot.board:preview;
  const width=$('board').clientWidth, gap=parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--gap'))||12;
  const size=(width-gap*2)/3, step=size+gap;
  for(const [value,tile] of tiles){
    const index=values.indexOf(value);tile.hidden=index<0;
    if(index>=0){tile.style.width=`${size}px`;tile.style.height=`${size}px`;tile.style.transform=`translate(${index%3*step}px,${Math.floor(index/3)*step}px)`;}
    const movable=Boolean(snapshot && snapshot.flags&8 && snapshot.hints&(1<<(8-value)));
    tile.classList.toggle('movable',movable);
    tile.disabled=!ui.connected||ui.busy||snapshot?.state!==1||Boolean(snapshot?.flags&1);
    tile.setAttribute('aria-label',`棋子 ${value}${movable?'，可以移动':''}`);
  }
  const blank=values.indexOf(0),marker=$('blank-marker');marker.hidden=blank<0;
  if(blank>=0){marker.style.width=`${size}px`;marker.style.height=`${size}px`;marker.style.transform=`translate(${blank%3*step}px,${Math.floor(blank/3)*step}px)`;}
  const isError=ui.connected && snapshot?.state===0 && snapshot.flags&2 && snapshot.entered<8;
  const error=$('input-marker');error.style.display=isError?'flex':'none';
  if(isError){const i=snapshot.entered;error.style.width=`${size}px`;error.style.height=`${size}px`;error.style.transform=`translate(${i%3*step}px,${Math.floor(i/3)*step}px)`;}
}
function render(){
  const s=ui.snapshot, connected=ui.connected, demo=Boolean(s?.flags&1), won=s?.state===2, error=Boolean(s?.flags&2),hint=Boolean(s?.flags&8);
  $('connect').classList.toggle('connected',connected);$('connection-text').textContent=connected?'EGO1 已连接':'连接开发板';
  $('live-dot').classList.toggle('live',connected);$('live-label').textContent=connected?'实板棋局 · 已同步':s?'实板棋局 · 已离线':'棋盘预览 · 等待连接';
  $('game-card').classList.toggle('offline',!connected);
  $('board-title').textContent=!connected?'准备好，移动一下。':won?'全部归位。就是现在。':demo?'跟着演示，找到节奏。':s?.state===0?'从一个数字开始。':'留一个空位，给下一步。';
  $('mode-badge').textContent=!connected?'等待连接':demo?`演示 · ${s.phase||'准备'}`:won?(s.flags&4?'通关统计':'已通关'):error?'输入无效':s.state===0?'逐格初始化':'正在游戏';
  $('mode-badge').className=`mode-badge${won?' won':error?' error':''}`;
  $('session-status').textContent=!connected?'STANDBY':won?'COMPLETE':demo?'DEMO':'LIVE';
  $('good-count').textContent=String(s?.good||0).padStart(2,'0');$('bad-count').textContent=String(s?.bad||0).padStart(2,'0');
  const lock=!connected||ui.busy;
  for(const id of ['new-game','custom','hint','reset','demo','binary-page'])$(id).disabled=lock;
  $('undo').disabled=lock||!Boolean(s?.flags&16)||demo;
  $('next-demo').disabled=lock||!demo;
  $('hint').setAttribute('aria-pressed',String(hint));$('binary-page').textContent=s?.flags&64?'HIGH':'LOW';
  $('demo-label').textContent=demo?'退出演示':'演示模式';
  $('entry-panel').hidden=!connected||s?.state!==0||demo;
  $('entry-label').textContent=`输入第 ${(s?.entered||0)+1} 个数字 · 数字键直接确认`;
  [...$('keypad').children].forEach((button,i)=>{button.disabled=lock;button.classList.toggle('used',Boolean(s?.board.slice(0,s.entered).includes(i)));});
  $('hint-chips').replaceChildren();
  if(connected && s?.state && hint){
    for(let value=1;value<=8;value++)if(s.hints&(1<<(8-value))){const chip=document.createElement('button');chip.className='hint-chip';chip.textContent=value;chip.disabled=lock||won||demo;chip.setAttribute('aria-label',`移动可用棋子 ${value}`);chip.addEventListener('click',()=>sendCommand(String(value)));$('hint-chips').append(chip);}
  }else{const note=document.createElement('span');note.className='muted-note';note.textContent=!connected?'等待实板棋局':s?.state===0?'完成九格输入后点亮':'按 H 开启提示';$('hint-chips').append(note);}
  $('hint-explanation').textContent=won?'棋局已归位。按 Enter 查看板上统计。':'亮起的数字，可以移入空位。';
  [...$('led-strip').children].forEach((dot,i)=>{const on=connected&&Boolean(s?.leds&(1<<(7-i)));dot.classList.toggle('on',on);dot.setAttribute('aria-label',`灯 ${i+1}${on?'亮':'灭'}`);});
  $('led-caption').textContent=hint?'1–8 号棋子的可移动状态':`有效 / 无效 · ${s?.flags&64?'高':'低'}四位`;
  $('sync-status').textContent=connected?'● SYNC':'OFFLINE';
  const scenes=['准备演示','初始化棋盘','无效移动','合法移动 · 通关','十进制统计','全暗复位'];
  $('board-message').textContent=!connected?(s?'重新连接，继续这场棋局':'连接 EGO1，开始一场实板游戏'):demo?scenes[s.phase]:(won?(s.flags&4?`板上统计：有效 ${s.good} / 无效 ${s.bad}。按 Enter 返回。`:`有效 ${s.good} 步，无效 ${s.bad} 步。按 Z 可以撤回。`):s.state===0?(error?'数字重复或超出范围，改正后继续。':`已输入 ${s.entered}/9 格。按数字键继续。`):error?'这颗棋子不与空位相邻，换一个试试。':'点击棋子，或用方向键移动空位。');
  $('progress-label').textContent=connected?(demo?`SCENE ${s.phase} / 5`:won?'NICE & SOLVED':s.state===0?`${s.entered} / 9`:'REAL HARDWARE'):'PREVIEW';
  $('demo-progress').hidden=!demo;[...$('demo-progress').children].forEach((bar,i)=>bar.classList.toggle('active',i<s?.phase));
  document.body.classList.toggle('busy',ui.busy);
  positionBoard();
}
async function runAction(body){
  if(!ui.connected){toast('先连接 EGO1，再开始游戏');return;}
  if(ui.busy)return;
  ui.busy=true;render();
  try{
    const data=await api('/api/action',body);
    if(['new_game','initialize'].includes(body.action)||body.command==='r')ui.resetClock=true;
    apply(data);
    if(body.action==='new_game')toast('新棋局已在 FPGA 上就绪。轮到你了。');
    if(body.action==='initialize')toast('自定义棋盘已写入开发板。');
  }catch(error){toast(error.message,true);}
  finally{ui.busy=false;render();}
}
function sendCommand(command){return runAction({action:'command',command});}
async function showConnection(){
  if(ui.busy)return;
  if(ui.connected){
    try{apply(await api('/api/disconnect',{}));}catch(error){toast(error.message,true);}return;
  }
  try{apply(await api('/api/status'));}catch(error){toast('本地桥接服务未连接，请运行 make gui',true);return;}
  $('port-select').replaceChildren();
  for(const port of ui.ports){const option=document.createElement('option');option.value=port;option.textContent=port.split('/').pop();$('port-select').append(option);}
  $('connect-confirm').disabled=ui.ports.length===0;
  $('port-help').textContent=ui.ports.length?'使用 USB 串口 B 通道，115200 · 8N1。':'没有找到 EGO1。请检查 USB 连接，然后关闭窗口重试。';
  $('connect-dialog').showModal();
}
$('connect').addEventListener('click',showConnection);
$('connect-confirm').addEventListener('click',async()=>{
  const button=$('connect-confirm');button.disabled=true;
  try{const data=await api('/api/connect',{port:$('port-select').value});apply(data);$('connect-dialog').close();toast('EGO1 已连接，当前棋局已同步。');}
  catch(error){$('port-help').textContent=error.message;toast(error.message,true);}
  finally{button.disabled=ui.ports.length===0;}
});
for(const button of $('levels').children)button.addEventListener('click',()=>{ui.steps=Number(button.dataset.steps);[...$('levels').children].forEach(b=>{const selected=b===button;b.classList.toggle('selected',selected);b.setAttribute('aria-checked',String(selected));});});
$('new-game').addEventListener('click',()=>runAction({action:'new_game',steps:ui.steps}));
$('undo').addEventListener('click',()=>sendCommand('u'));
$('hint').addEventListener('click',()=>sendCommand('v'));
$('reset').addEventListener('click',()=>sendCommand('r'));
$('binary-page').addEventListener('click',()=>sendCommand('h'));
$('demo').addEventListener('click',()=>runAction({action:'demo'}));
$('next-demo').addEventListener('click',()=>sendCommand('n'));
function customValues(){const text=$('custom-input').value.replace(/\s/g,'');if(!/^[0-8]{9}$/.test(text)||new Set(text).size!==9)throw new Error('请输入 0–8 九个数字，每个数字只能出现一次。');return [...text].map(Number);}
function customPreview(){
  const chars=$('custom-input').value.replace(/\s/g,'');$('custom-preview').replaceChildren();
  for(let i=0;i<9;i++){const cell=document.createElement('span');cell.textContent=chars[i]==='0'?'':chars[i]||'·';if(chars[i]==='0')cell.className='empty';$('custom-preview').append(cell);}
  try{const values=customValues();let inversions=0;for(let i=0;i<9;i++)for(let j=i+1;j<9;j++)if(values[i]&&values[j]&&values[i]>values[j])inversions++;$('custom-error').textContent=inversions%2?'这个布局不可解，但仍可写入做课程测试。':'布局合法，准备写入。';$('custom-confirm').disabled=false;}
  catch(error){$('custom-error').textContent=error.message;$('custom-confirm').disabled=true;}
}
$('custom').addEventListener('click',()=>{customPreview();$('custom-dialog').showModal();$('custom-input').focus();$('custom-input').select();});
$('custom-input').addEventListener('input',customPreview);
$('custom-confirm').addEventListener('click',async()=>{try{const values=customValues();$('custom-dialog').close();await runAction({action:'initialize',board:values});}catch(error){toast(error.message,true);}});
for(const button of document.querySelectorAll('.close-dialog'))button.addEventListener('click',()=>button.closest('dialog').close());
for(const dialog of document.querySelectorAll('dialog')){
  dialog.addEventListener('click',event=>{if(event.target===dialog)dialog.close();});
  dialog.querySelector('form').addEventListener('submit',event=>{event.preventDefault();$(dialog.id==='custom-dialog'?'custom-confirm':'connect-confirm').click();});
}
document.addEventListener('keydown',event=>{
  if(event.repeat||event.isComposing||event.ctrlKey||event.metaKey||event.altKey||document.querySelector('dialog[open]')||['INPUT','SELECT','TEXTAREA'].includes(document.activeElement?.tagName))return;
  const key=event.key.toLowerCase();
  const directions={arrowup:'up',arrowdown:'down',arrowleft:'left',arrowright:'right'};
  if(directions[key]){event.preventDefault();if(ui.connected&&ui.snapshot?.state===1&&!(ui.snapshot.flags&1))runAction({action:'direction',direction:directions[key]});return;}
  if(!ui.connected)return;
  if(/^[0-9]$/.test(key)&&ui.snapshot?.state!==2&&!(ui.snapshot?.flags&1)){event.preventDefault();sendCommand(key);}
  else if(key==='z'){$('undo').click();event.preventDefault();}
  else if(key==='h'){$('hint').click();event.preventDefault();}
  else if(key==='r'){$('new-game').click();event.preventDefault();}
  else if(key==='d'){$('demo').click();event.preventDefault();}
  else if(key===' '){if(ui.snapshot?.flags&1){event.preventDefault();$('next-demo').click();}}
  else if(key==='enter'&&ui.snapshot?.state===2){event.preventDefault();sendCommand('c');}
});
new ResizeObserver(positionBoard).observe($('board'));
setInterval(()=>{const ms=ui.started?performance.now()-ui.started:ui.elapsed;const seconds=Math.floor(ms/1000);$('elapsed').textContent=`${String(Math.floor(seconds/60)).padStart(2,'0')}:${String(seconds%60).padStart(2,'0')}`;},500);
setInterval(async()=>{if(pollPending||ui.busy||document.hidden)return;pollPending=true;try{apply(await api('/api/status'));}catch(error){if(ui.connected){ui.connected=false;updateClock(ui.snapshot,ui.snapshot);render();toast('本地桥接服务已停止，请重新运行 make gui',true);}}finally{pollPending=false;}},250);
render();
api('/api/status').then(apply).catch(()=>toast('请先运行 make gui，启动实板连接服务。',true));
