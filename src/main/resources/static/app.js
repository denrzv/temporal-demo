const $ = (s, root = document) => root.querySelector(s);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const label = value => String(value ?? '').toLowerCase().replaceAll('_', ' ').replace(/^./, c => c.toUpperCase());
const badge = (value, text = label(value)) => `<span class="badge ${esc(String(value).toLowerCase())}">${esc(text)}</span>`;
const shortId = id => id.slice(0, 11).toUpperCase();
const date = timestamp => new Date(timestamp).toLocaleDateString(undefined, {month:'short', day:'numeric'});
const time = timestamp => new Date(timestamp).toLocaleTimeString(undefined, {hour:'2-digit', minute:'2-digit', second:'2-digit'});
const state = {requests:[], routes:[], directory:{agents:[], groups:[]}, config:{}, agent:localStorage.getItem('relay-agent') || 'alex', filter:'all', search:'', detail:null};
let polling = false;
const content = $('#content'), dialog = $('#dialog');
const currentAgent = () => state.directory.agents.find(a => a.id === state.agent);
const groupName = id => state.directory.groups.find(g => g.id === id)?.name || id;
const agentName = id => state.directory.agents.find(a => a.id === id)?.name || id;
const canWork = task => currentAgent()?.groupIds.includes(task.groupId) && currentAgent()?.taskTypes.includes(task.definition.type);
const inbox = () => state.requests.flatMap(r => (r.execution?.tasks || []).filter(t => t.status === 'WAITING' && canWork(t) && (!t.assignee || t.assignee === state.agent)).map(t => ({r,t})));
const page = () => (location.hash.slice(1).split('/')[0] || 'requests');
async function api(path, options = {}) {
  const response = await fetch('/api' + path, {headers:{'Content-Type':'application/json'}, ...options, body:options.body ? JSON.stringify(options.body) : undefined});
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || `Request failed (${response.status})`);
  }
  return response.status === 204 || response.headers.get('content-length') === '0' ? null : response.text().then(t => t ? JSON.parse(t) : null);
}
function toast(message) {
  const el = $('#toast'); el.textContent = message; el.classList.add('visible');
  clearTimeout(toast.timer); toast.timer = setTimeout(() => el.classList.remove('visible'), 4000);
}
function showDialog(title, subtitle, body, wide = false) {
  dialog.classList.toggle('wide', wide);
  $('#dialog-body').innerHTML = `<div class="dialog-head"><div><h2>${esc(title)}</h2><p class="subtext">${esc(subtitle)}</p></div><button class="close" data-action="close" aria-label="Close dialog">×</button></div><div class="dialog-content">${body}</div>`;
  dialog.showModal();
}
function showError(message) {
  let error = $('#form-error');
  if (!error) { error = document.createElement('div'); error.id = 'form-error'; error.className = 'error'; error.setAttribute('role','alert'); $('.dialog-content').prepend(error); }
  error.textContent = message;
}
function setNavigation() {
  const p = page();
  document.querySelectorAll('nav a').forEach(a => a.classList.toggle('active', a.dataset.page === p || (p === 'request' && a.dataset.page === 'requests')));
  $('#breadcrumb').textContent = ({requests:'Service requests',request:'Service requests',inbox:'My task inbox',routes:'Execution routes',directory:'Agents & groups'})[p] || 'Service requests';
  $('#request-count').textContent = state.requests.length;
  $('#inbox-count').textContent = inbox().length;
  $('#avatar').textContent = currentAgent()?.name.split(' ').map(n => n[0]).join('') || '';
}
function render() {
  setNavigation();
  const active = document.activeElement, focusId = active?.id, cursor = active?.selectionStart;
  switch(page()) {
    case 'request': renderDetail(); break;
    case 'routes': renderRoutes(); break;
    case 'inbox': renderInbox(); break;
    case 'directory': renderDirectory(); break;
    default: renderRequests();
  }
  if (focusId && document.getElementById(focusId) && !dialog.open) {
    const el = document.getElementById(focusId); el.focus();
    if (typeof cursor === 'number' && el.setSelectionRange) el.setSelectionRange(cursor,cursor);
  }
  requestAnimationFrame(drawEdges);
}
function progress(request) {
  const tasks = request.execution?.tasks || [];
  return `<div class="mini-progress" aria-hidden="true">${tasks.map(t => `<i class="${t.status === 'COMPLETED' ? 'done' : ['WAITING','RUNNING'].includes(t.status) ? 'current' : ''}"></i>`).join('')}</div><span class="tiny">${tasks.filter(t => t.status === 'COMPLETED').length} of ${tasks.length} completed</span>`;
}
function empty(title, description, action = '') {
  return `<div class="empty"><div class="empty-mark">⑂</div><h3>${esc(title)}</h3><p>${esc(description)}</p>${action}</div>`;
}
function renderRequests() {
  const waiting = state.requests.reduce((n,r) => n + (r.execution?.tasks.filter(t => t.status === 'WAITING').length || 0), 0);
  const filtered = state.requests.filter(r => (state.filter === 'all' || (state.filter === 'active' ? ['RUNNING','PENDING'].includes(r.status) : r.status === state.filter.toUpperCase())) && `${r.title} ${r.id} ${r.customerRef} ${r.requestType}`.toLowerCase().includes(state.search.toLowerCase()));
  content.innerHTML = `<div class="eyebrow">OPERATIONS OVERVIEW</div><div class="page-head"><div><h1>Service requests</h1><p class="subtext">Every request. Every handoff. One clear picture.</p></div><div class="head-actions"><button class="primary" data-action="new-request">＋ New request</button></div></div>
    <div class="stats">${[
      ['Total requests',state.requests.length,'Latest 100 requests','▤',''],
      ['In progress',state.requests.filter(r => ['RUNNING','PENDING'].includes(r.status)).length,'Moving through execution','↗','active-stat'],
      ['Awaiting agents',waiting,'Internal tasks ready for action','◷',''],
      ['Completed',state.requests.filter(r => r.status === 'COMPLETED').length,'Execution plans finished','✓','']
    ].map(([name,n,note,icon,cls]) => `<div class="stat ${cls}"><div class="stat-top">${name}<span class="stat-symbol">${icon}</span></div><strong>${n.toString().padStart(2,'0')}</strong><small>${note}</small></div>`).join('')}</div>
    <section class="panel"><div class="panel-head"><h2>Request register</h2><span class="tiny">Live workflow state · refreshes every 5s</span></div><div class="toolbar"><div class="tabs" aria-label="Filter requests">${['all','active','completed','failed'].map(f => `<button data-action="filter" data-id="${f}" class="${f === state.filter ? 'selected' : ''}" aria-pressed="${f === state.filter}">${label(f)}</button>`).join('')}</div><input id="request-search" class="search" placeholder="Search requests…" aria-label="Search requests" value="${esc(state.search)}"></div>
    ${filtered.length ? `<div class="table-wrap"><table><thead><tr><th>REQUEST</th><th>TYPE</th><th>STATUS</th><th>EXECUTION</th><th>CREATED</th><th></th></tr></thead><tbody>${filtered.map(r => `<tr class="clickable" data-action="open-request" data-id="${esc(r.id)}"><td><a href="#request/${esc(r.id)}" class="request-title">${esc(r.title)}</a><span class="request-id">${esc(shortId(r.id))} · ${esc(r.customerRef)}</span></td><td>${badge(r.requestType)}</td><td>${badge(r.status)}</td><td>${progress(r)}</td><td>${date(r.createdAt)}</td><td>↗</td></tr>`).join('')}</tbody></table></div>` : empty(state.requests.length ? 'No matching requests' : 'A clear path from request to resolution', state.requests.length ? 'Try a different filter or search term.' : 'Start a request to watch Temporal coordinate agent work and calls to external systems.', state.requests.length ? '' : '<button data-action="new-request" class="primary">Create your first request →</button>')}
    <div class="table-foot"><span>${filtered.length} request${filtered.length !== 1 ? 's' : ''} shown</span><span>Routes are versioned · executions are durable</span></div></section>
    <div class="notice"><span class="notice-symbol">⑂</span><div><strong>One request, a coordinated execution plan.</strong><p>Internal tasks go to eligible agents. External tasks query or command connected systems. Temporal keeps each step moving.</p></div></div>`;
}
function flowHtml(tasks) {
  const definitions = tasks.map(t => t.definition || t);
  const levels = new Map();
  for (let n = 0; n < definitions.length; n++) for (const t of definitions) if (!levels.has(t.id) && t.dependsOn.every(d => levels.has(d))) levels.set(t.id, t.dependsOn.length ? Math.max(...t.dependsOn.map(d => levels.get(d))) + 1 : 0);
  const max = Math.max(0,...levels.values());
  return `<div class="flow-scroll"><div class="flow"><svg aria-hidden="true"><defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#b1bfa4"/></marker></defs><g class="edges"></g></svg>${Array.from({length:max+1},(_,i) => `<div class="flow-column">${tasks.filter(t => levels.get((t.definition || t).id) === i).map(t => {
    const d = t.definition || t, live = !!t.definition, status = t.status || 'PENDING';
    return `<button class="task-node ${status.toLowerCase()}" data-node="${esc(d.id)}" data-deps="${esc(d.dependsOn.join(','))}" data-action="${live ? 'inspect-task' : 'noop'}" data-id="${esc(d.id)}">${d.when ? `<span class="node-condition">if ${esc(d.when.outcome)}</span>` : ''}<span class="node-kind">${d.kind === 'INTERNAL' ? '♧ Agent task' : d.kind === 'EXTERNAL_QUERY' ? '↙ System query' : '↗ System command'}<span>${status === 'COMPLETED' ? '✓' : ''}</span></span><span class="node-name">${esc(d.name)}</span><span class="node-group">${esc(d.kind === 'INTERNAL' ? groupName(t.groupId || d.groupId) : d.operation)}</span>${badge(status, live ? label(status) : label(d.kind))}</button>`;
  }).join('')}</div>`).join('')}</div></div>`;
}
function drawEdges() {
  document.querySelectorAll('.flow').forEach(flow => {
    const columns = flow.querySelectorAll('.flow-column').length;
    flow.style.setProperty('--node-width', Math.min(196, Math.max(145, Math.floor((flow.parentElement.clientWidth - 62 - (columns - 1) * 24) / columns))) + 'px');
    const origin = flow.getBoundingClientRect(), nodes = [...flow.querySelectorAll('[data-node]')];
    $('.edges',flow).innerHTML = nodes.flatMap(node => node.dataset.deps.split(',').filter(Boolean).map(dep => {
      const source = nodes.find(n => n.dataset.node === dep); if (!source) return '';
      const a = source.getBoundingClientRect(), b = node.getBoundingClientRect();
      const x1 = a.right - origin.left, y1 = a.top + a.height/2 - origin.top, x2 = b.left - origin.left, y2 = b.top + b.height/2 - origin.top, mid = (x1+x2)/2;
      return `<path d="M${x1},${y1} C${mid},${y1} ${mid},${y2} ${x2-2},${y2}" fill="none" stroke="#b1bfa4" stroke-width="1.3" marker-end="url(#arrow)"/>`;
    })).join('');
  });
}
function renderDetail() {
  const r = state.detail;
  if (!r || r.id !== decodeURIComponent(location.hash.split('/')[1] || '')) { content.innerHTML = '<div class="spinner">Loading execution…</div>'; return; }
  const s = r.execution;
  content.innerHTML = `<a class="back" href="#requests">← All service requests</a><div class="page-head"><div><div class="eyebrow">${esc(shortId(r.id))}</div><h1>${esc(r.title)}</h1><div class="detail-meta">${badge(r.requestType)}${badge(r.status)}<span>${esc(r.customerRef)}</span><span>Created ${date(r.createdAt)}</span></div></div><div class="head-actions">${r.status === 'RUNNING' ? '<button class="danger" data-action="cancel-request">Cancel request</button>' : ''}<a class="small" href="${esc(temporalUrl(r.id))}" target="_blank" rel="noreferrer">Temporal history ↗</a></div></div>
    ${r.error ? `<div class="error error-banner">${esc(r.error)}</div>` : ''}${!s ? empty('Waiting for workflow state', 'The durable start dispatcher submits pending requests to Temporal. This page will update automatically.') : `
    <div class="detail-layout"><div><section class="panel"><div class="panel-head"><h2>Execution flow</h2><span class="tiny">${esc(s.route.name)} · v${s.route.version}</span></div>${flowHtml(s.tasks)}<div class="flow-legend"><span><i class="legend-dot complete"></i>Completed</span><span><i class="legend-dot wait"></i>Waiting for agent</span><span><i class="legend-dot"></i>Pending / skipped</span></div></section>
    <section class="panel section-gap"><div class="panel-head"><h2>Plan tasks</h2><span class="tiny">${s.tasks.length} steps</span></div><div class="task-list">${s.tasks.map(t => `<div class="task-row"><div><h3>${esc(t.definition.name)} ${t.slaBreached ? badge('failed','SLA exceeded') : ''}</h3><p>${esc(t.definition.kind === 'INTERNAL' ? `${groupName(t.groupId)} · ${label(t.definition.type)}${t.assignee ? ' · ' + agentName(t.assignee) : ''}` : t.definition.operation)}${t.outcome ? ' · ' + esc(t.outcome) : ''}</p></div><div class="task-row-actions">${badge(t.status)}${t.status === 'WAITING' && canWork(t) && !t.assignee ? `<button class="small primary" data-action="claim" data-id="${esc(t.definition.id)}">Claim task</button>` : ''}${t.status === 'WAITING' && t.assignee === state.agent ? `<button class="small primary" data-action="complete-dialog" data-id="${esc(t.definition.id)}">Complete</button>` : ''}<button class="small" data-action="inspect-task" data-id="${esc(t.definition.id)}">Details</button></div></div>`).join('')}</div></section></div>
    <aside><section class="panel info-card"><h2>Request context</h2><dl><div><dt>Route version</dt><dd>v${s.route.version} · pinned</dd></div><div><dt>Execution</dt><dd>${esc(label(s.status))}</dd></div><div><dt>Customer</dt><dd>${esc(s.customerRef)}</dd></div><div><dt>Agent tasks</dt><dd>${s.tasks.filter(t => t.definition.kind === 'INTERNAL').length}</dd></div></dl></section><section class="panel section-gap"><div class="panel-head"><h2>Activity timeline</h2></div><div class="timeline">${[...s.events].reverse().map(e => `<div class="timeline-item"><p>${esc(e.message)}</p><time>${time(e.timestamp)}</time></div>`).join('')}</div></section></aside></div>`}`;
}
function temporalUrl(id) { return `${state.config.temporalUiUrl}/namespaces/${encodeURIComponent(state.config.namespace)}/workflows/${encodeURIComponent(id)}`; }
function renderRoutes() {
  content.innerHTML = `<div class="eyebrow">PROCESS DESIGN</div><div class="page-head"><div><h1>Execution routes</h1><p class="subtext">Define how a request moves from intake to resolution.</p></div><button class="primary" data-action="new-route">＋ New route</button></div><div class="route-grid">${state.routes.map(r => `<article class="route-card"><div class="route-top"><span class="route-icon">⑂</span>${badge(r.requestType)}</div><h2>${esc(r.name)}</h2><p>${esc(r.description)}</p><div class="route-detail"><span>v${r.version}</span><span>${r.tasks.length} steps</span><span>${r.tasks.filter(t => t.kind === 'INTERNAL').length} agent tasks</span></div><button data-action="edit-route" data-id="${esc(r.id)}">View & edit route →</button></article>`).join('')}</div><div class="notice"><span class="notice-symbol">◈</span><div><strong>Edit confidently with versioned routes.</strong><p>Saving publishes a new version for future requests. In-flight requests keep their original execution plan. Dependencies enable parallel steps; outcome conditions define branches.</p></div></div>`;
}
function renderInbox() {
  const tasks = inbox();
  content.innerHTML = `<div class="eyebrow">AGENT WORKSPACE</div><div class="page-head"><div><h1>My task inbox</h1><p class="subtext">Work available to ${esc(currentAgent()?.name)} across eligible groups and task types.</p></div>${badge('internal',`${tasks.length} available`)}</div><section class="panel"><div class="panel-head"><h2>Ready for your attention</h2><span class="tiny">Claim a task to take ownership</span></div>${tasks.length ? tasks.map(({r,t}) => `<div class="inbox-item"><div>${badge(t.definition.type)}<h3>${esc(t.definition.name)}</h3><p>${esc(r.title)} · ${esc(groupName(t.groupId))} · ${esc(shortId(r.id))}</p></div><div><button class="small" data-action="open-request" data-id="${esc(r.id)}">View request ↗</button><button class="small primary" data-action="inbox-task" data-id="${esc(t.definition.id)}" data-request="${esc(r.id)}">${t.assignee ? 'Continue task' : 'Claim task'}</button></div></div>`).join('') : empty('Your inbox is clear', 'Agent tasks appear here when their dependencies finish and your group and task type match. Switch demo agents to explore other queues.')}</section><div class="notice"><span class="notice-symbol">♧</span><div><strong>Eligibility follows the work.</strong><p>Agents need both membership in the assigned group and support for the task type. A task can have only one claim at a time.</p></div></div>`;
}
function renderDirectory() {
  content.innerHTML = `<div class="eyebrow">PEOPLE & CAPABILITIES</div><div class="page-head"><div><h1>Agents & groups</h1><p class="subtext">The demo directory connects task types to the people who can process them.</p></div></div><div class="directory-grid">${state.directory.groups.map(g => `<section class="panel group-card"><h2>${esc(g.name)}</h2><p class="subtext">${esc(g.id)}</p><div class="chips">${g.taskTypes.map(t => badge('internal',label(t))).join('')}</div>${state.directory.agents.filter(a => a.groupIds.includes(g.id)).map(a => `<div class="group-person"><span class="avatar">${esc(a.name.split(' ').map(n => n[0]).join(''))}</span><div>${esc(a.name)}<p class="tiny">${a.taskTypes.map(label).join(' · ')}</p></div></div>`).join('')}</section>`).join('')}</div><p class="help section-gap">Demo directory is configured in Catalog.java. The agent selector simulates identities; authentication is not enabled in this local example.</p>`;
}
function newRequest() {
  showDialog('New service request','Choose a route to create a durable execution plan.', `<form id="request-form"><label for="route-id">Execution route</label><select id="route-id" name="routeId">${state.routes.map(r => `<option value="${esc(r.id)}">${esc(r.name)} · v${r.version}</option>`).join('')}</select><p class="help" id="route-help">${esc(state.routes[0]?.description)}</p><label for="request-title">Request title</label><input id="request-title" name="title" required maxlength="160" placeholder="e.g. Enable a new service connection"><label for="customer-ref">Customer reference</label><input id="customer-ref" name="customerRef" required pattern="[A-Za-z0-9_-]{1,64}" value="CUST-1001" maxlength="64"><p class="help">External queries and commands use this customer reference.</p><details><summary class="help">Advanced · integration failure demo</summary><label for="simulate-failure">Command behavior</label><select id="simulate-failure"><option value="false">Normal execution</option><option value="true">Simulate system outage (observe retries)</option></select></details><div class="dialog-actions"><button type="button" data-action="close">Cancel</button><button class="primary" type="submit">Create request →</button></div></form>`);
}
function editRoute(id) {
  const existing = state.routes.find(r => r.id === id);
  const route = existing || {...structuredClone(state.routes.find(r => r.id === 'custom')),id:'my-route',version:0,name:'My custom route',requestType:'MY_TYPE',description:'A custom service request process.'};
  showDialog(existing ? 'Edit execution route' : 'Create execution route', existing ? `${route.name} · publishing v${route.version + 1}` : 'Start with a template and define your own request type.', `<form id="route-form" data-existing="${existing ? esc(id) : ''}"><p class="help">Edit the route definition below. INTERNAL tasks specify a group and task type. EXTERNAL_QUERY and EXTERNAL_COMMAND tasks specify an operation. Dependencies form a directed acyclic graph; “when” checks a dependency’s outcome.</p><label for="route-json">Route definition (JSON)</label><textarea class="code" id="route-json" spellcheck="false" required>${esc(JSON.stringify(route,null,2))}</textarea><p class="help">Supported query operations: LOOKUP_CUSTOMER, CHECK_ELIGIBILITY. Commands: PROVISION_SERVICE, ISSUE_REFUND, SEND_NOTIFICATION. Available group capabilities are listed under Agents & groups.</p><div class="dialog-actions"><button type="button" data-action="close">Cancel</button>${existing ? `<button type="button" data-action="preview-route" data-id="${esc(id)}">Preview current flow</button>` : ''}<button class="primary" type="submit">${existing ? 'Publish new version' : 'Create route'}</button></div></form>`,true);
}
function inspectTask(id) {
  const t = state.detail?.execution?.tasks.find(t => t.definition.id === id); if (!t) return;
  const d = t.definition;
  showDialog(d.name, `${label(d.kind)} · ${label(d.type)}`, `${badge(t.status)}${t.slaBreached ? ' ' + badge('failed','SLA exceeded') : ''}<p class="help section-gap">${d.dependsOn.length ? 'Depends on: ' + esc(d.dependsOn.join(', ')) : 'Starts when the request begins.'}${d.when ? ' · Runs if ' + esc(d.when.taskId) + ' returns ' + esc(d.when.outcome) : ''}</p>${d.kind === 'INTERNAL' ? `<p class="help">Assigned group: <strong>${esc(groupName(t.groupId))}</strong><br>Agent: ${esc(t.assignee ? agentName(t.assignee) : 'Unclaimed')}<br>SLA: ${d.slaSeconds} seconds</p>${t.status === 'WAITING' ? `<form id="reassign-form" data-task="${esc(id)}"><label for="target-group">Route to a compatible group</label><select id="target-group">${state.directory.groups.filter(g => g.taskTypes.includes(d.type)).map(g => `<option value="${esc(g.id)}" ${g.id === t.groupId ? 'selected' : ''}>${esc(g.name)}</option>`).join('')}</select><p class="help">Reassigning releases any current claim. Demo routing operator action.</p><button class="small" type="submit">Reassign group</button></form>` : ''}` : `<p class="help">Operation: ${esc(d.operation)}<br>Idempotency key: ${esc(state.detail.id + ':' + id)}<br>Retry policy: up to 5 attempts, 15s per attempt, 2 minute overall budget.</p>`}${Object.keys(t.result || {}).length ? `<label>System response</label><pre class="result-data">${esc(JSON.stringify(t.result,null,2))}</pre>` : ''}${t.note ? `<label>Completion note</label><p class="help">${esc(t.note)}</p>` : ''}<div class="dialog-actions"><button data-action="close">Close</button>${t.status === 'WAITING' && canWork(t) && !t.assignee ? `<button class="primary" data-action="claim" data-id="${esc(id)}">Claim task</button>` : ''}${t.status === 'WAITING' && t.assignee === state.agent ? `<button class="primary" data-action="complete-dialog" data-id="${esc(id)}">Complete task</button>` : ''}</div>`);
}
function completeDialog(id) {
  const t = state.detail?.execution?.tasks.find(t => t.definition.id === id); if (!t) return;
  if (dialog.open) dialog.close();
  showDialog('Complete task',t.definition.name, `<form id="complete-form" data-task="${esc(id)}"><p class="help">Completing as ${esc(currentAgent().name)}. The result determines which route branches execute next.</p><label for="outcome">Outcome</label><select id="outcome">${t.definition.outcomes.map(o => `<option value="${esc(o)}">${esc(label(o))}</option>`).join('')}</select><label for="note">Agent note (optional)</label><textarea id="note" maxlength="2000" placeholder="What was checked or decided?"></textarea><div class="dialog-actions"><button type="button" data-action="close">Cancel</button><button class="primary" type="submit">Complete task →</button></div></form>`);
}
async function taskAction(requestId, taskId, action, body = {}) {
  await api(`/requests/${encodeURIComponent(requestId)}/tasks/${encodeURIComponent(taskId)}/${action}`,{method:'POST',body:{agentId:state.agent,...body}});
  await refresh(true);
}
async function refresh(force = false) {
  if (polling) return;
  polling = true;
  try {
    const requests = await api('/requests');
    let detail = null;
    if (page() === 'request') detail = await api('/requests/' + encodeURIComponent(decodeURIComponent(location.hash.split('/')[1] || '')));
    const changed = JSON.stringify(requests) !== JSON.stringify(state.requests) || JSON.stringify(detail) !== JSON.stringify(state.detail);
    state.requests = requests; state.detail = detail;
    if ((force || changed) && !dialog.open) render(); else setNavigation();
  } catch (e) { if (force) { content.innerHTML = `<div class="error" role="alert">${esc(e.message)}</div><button data-action="refresh">Try again</button>`; } }
  finally { polling = false; }
}
document.addEventListener('click', async event => {
  const target = event.target.closest('[data-action]'); if (!target) return;
  const {action,id} = target.dataset;
  try {
    switch(action) {
      case 'close': dialog.close(); render(); break;
      case 'new-request': newRequest(); break;
      case 'new-route': editRoute(); break;
      case 'edit-route': editRoute(id); break;
      case 'preview-route': { const route = state.routes.find(r => r.id === id); dialog.close(); showDialog(route.name,'Preview of the currently published route.',flowHtml(route.tasks),true); requestAnimationFrame(drawEdges); break; }
      case 'filter': state.filter = id; render(); break;
      case 'open-request': location.hash = 'request/' + id; break;
      case 'inspect-task': inspectTask(id); break;
      case 'complete-dialog': completeDialog(id); break;
      case 'claim': target.disabled = true; await taskAction(state.detail.id,id,'claim'); if (dialog.open) dialog.close(); render(); toast('Task claimed'); break;
      case 'inbox-task': {
        const requestId = target.dataset.request; const task = state.requests.find(r => r.id === requestId).execution.tasks.find(t => t.definition.id === id);
        target.disabled = true;
        if (!task.assignee) await taskAction(requestId,id,'claim');
        location.hash = 'request/' + requestId; break;
      }
      case 'cancel-request': showDialog('Cancel this request?','Active work will stop. Completed external commands cannot be undone.',`<p class="help">Cancellation stops pending tasks and requests cancellation of running activities. An external command may already have taken effect; this demo does not compensate completed actions.</p><div class="dialog-actions"><button data-action="close">Keep request</button><button class="danger" data-action="confirm-cancel">Cancel request</button></div>`); break;
      case 'confirm-cancel': target.disabled = true; await api(`/requests/${encodeURIComponent(state.detail.id)}/cancel`,{method:'POST'}); dialog.close(); await refresh(true); toast('Request cancelled'); break;
      case 'refresh': await refresh(true); break;
    }
  } catch(e) { if (dialog.open) showError(e.message); else toast(e.message); }
  finally { if (target.isConnected) target.disabled = false; }
});
document.addEventListener('submit', async event => {
  event.preventDefault(); const form = event.target, submit = form.querySelector('[type="submit"]');
  if (submit) submit.disabled = true;
  try {
    switch(form.id) {
      case 'request-form': {
        const body = Object.fromEntries(new FormData(form)); body.data = {simulateFailure:$('#simulate-failure').value};
        const r = await api('/requests',{method:'POST',body}); dialog.close(); location.hash = 'request/' + r.id; toast('Request created · awaiting Temporal dispatch'); await refresh(true); break;
      }
      case 'route-form': {
        let route; try { route = JSON.parse($('#route-json').value); } catch { throw new Error('The route definition must be valid JSON.'); }
        const existing = form.dataset.existing;
        await api('/routes' + (existing ? '/' + encodeURIComponent(existing) : ''),{method:existing ? 'PUT' : 'POST',body:route});
        state.routes = await api('/routes'); dialog.close(); render(); toast('Route published'); break;
      }
      case 'complete-form': await taskAction(state.detail.id,form.dataset.task,'complete',{outcome:$('#outcome').value,note:$('#note').value}); dialog.close(); render(); toast('Task completed'); break;
      case 'reassign-form': await taskAction(state.detail.id,form.dataset.task,'reassign',{groupId:$('#target-group').value}); dialog.close(); render(); toast('Task reassigned'); break;
    }
  } catch(e) { showError(e.message); }
  finally { if (submit?.isConnected) submit.disabled = false; }
});
document.addEventListener('input', event => { if (event.target.id === 'request-search') { state.search = event.target.value; render(); } });
document.addEventListener('change', event => {
  if (event.target.id === 'agent-select') { state.agent = event.target.value; localStorage.setItem('relay-agent',state.agent); render(); }
  if (event.target.id === 'route-id') $('#route-help').textContent = state.routes.find(r => r.id === event.target.value)?.description || '';
});
window.addEventListener('hashchange', async () => { render(); await refresh(true); });
window.addEventListener('resize', drawEdges);
dialog.addEventListener('click', event => { if (event.target === dialog) dialog.close(); });
async function init() {
  try {
    [state.routes,state.directory,state.config] = await Promise.all([api('/routes'),api('/directory'),api('/config')]);
    if (!currentAgent()) state.agent = state.directory.agents[0].id;
    $('#agent-select').innerHTML = state.directory.agents.map(a => `<option value="${esc(a.id)}" ${a.id === state.agent ? 'selected' : ''}>${esc(a.name)}</option>`).join('');
    $('#temporal-link').href = state.config.temporalUiUrl;
    await refresh(true);
    setInterval(() => refresh(),5000);
  } catch(e) { content.innerHTML = `<div class="error" role="alert">${esc(e.message)}</div>`; }
}
init();
