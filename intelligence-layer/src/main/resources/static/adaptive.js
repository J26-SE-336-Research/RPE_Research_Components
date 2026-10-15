'use strict';
const $ = id => document.getElementById(id);
const el = (tag, value, className) => { const node = document.createElement(tag); if (value !== undefined) node.textContent = value; if (className) node.className = className; return node; };
let snapshot = null, example = null, mode = 'live', selected = null, busy = false, demoBusy = false, initial = true, reviewItem = null;
const fmt = value => Number.isFinite(value) ? new Intl.NumberFormat(undefined, {maximumFractionDigits: 1}).format(value) : '—';
const ms = value => Number.isFinite(value) ? `${fmt(value)} ms` : '—';
const route = target => (target || '').split(' | ')[0];
const human = {PENDING_APPROVAL:'Needs approval', APPROVED:'Approved', AUTOMATIC:'Automatic', REJECTED:'Rejected', WAITING_FOR_COMPONENT_3:'Not sent yet', READY:'Ready to send', SENT:'Sent, awaiting confirmation', APPLIED:'Applied', FAILED:'Delivery failed', EXPIRED:'Expired', SUPERSEDED:'No longer current'};
async function api(path = '', body) {
  const response = await fetch('/api/v2/intelligence' + path, {method: body === undefined ? 'GET' : 'POST',
    headers: {'Content-Type':'application/json', ...($('token').value ? {'X-RRPE-Control-Token':$('token').value} : {})},
    ...(body === undefined ? {} : {body:JSON.stringify(body)}), signal:AbortSignal.timeout(path === '/connections' ? 30000 : 15000)});
  const result = await response.json(); if (!response.ok) throw new Error(result.detail || `Request failed (${response.status})`); return result;
}
function error(message) { $('feedback').textContent = message; $('feedback').hidden = false; }
function currentChanges() { return ((mode === 'examples' ? example?.changes : snapshot?.changes) || []).filter(c => c.proposal.action === 'CHAIN_DEADLINE'); }
function entries() {
  if (mode === 'examples') {
    if (!example) return [];
    const found = new Map();
    for (const measurement of example.chains || []) found.set(measurement.window.chain, {target:measurement.window.chain, measurement, assessment:null});
    for (const outcome of example.outcomes || []) {
      const assessment = outcome.assessment;
      if (assessment) found.set(assessment.target, {...found.get(assessment.target), target:assessment.target, assessment});
    }
    return [...found.values()];
  }
  const found = new Map();
  for (const deadline of snapshot?.collection.configuredDeadlines || []) {
    const target = `${deadline.method} ${deadline.route}`;
    found.set(target, {target, configuredDeadline:deadline, measurement:null, assessment:null, configuredOnly:true});
  }
  for (const data of snapshot?.collection.chains || []) found.set(data.window.chain, {target:data.window.chain, measurement:data, assessment:null});
  for (const a of snapshot?.assessments || []) if (a.source === 'TEMPO') found.set(a.target, {...found.get(a.target),target:a.target,assessment:a});
  for (const c of currentChanges()) if (!found.has(c.proposal.target)) found.set(c.proposal.target, {target:c.proposal.target,measurement:null,assessment:null});
  return [...found.values()];
}
function setMode(next) { mode = next; selected = null; $('feedback').hidden = true; render(); if (next === 'examples' && !example && snapshot?.mockEnabled) runExample(); }
function renderServices(data) {
  const synthetic = mode === 'examples', collection = snapshot?.collection;
  const names = new Set(synthetic ? [] : collection?.configuredServices || []);
  const measurements = new Map((synthetic ? [] : collection?.measurements || []).map(item => [item.window.service, item]));
  const guards = new Map((synthetic ? [] : collection?.deadlineGuards || []).map(item => [item.target, item]));
  for (const name of measurements.keys()) names.add(name);
  for (const name of guards.keys()) names.add(name);
  for (const chain of data) for (const name of chain.measurement?.window.services || chain.target.split(' | ')[1]?.split(' > ') || []) names.add(name);
  $('services-title').textContent = synthetic ? 'Services in this example' : 'Live services';
  $('service-count').textContent = `${names.size} services`;
  $('services-caption').textContent = synthetic ? `This example contains ${data.length} request ${data.length === 1 ? 'chain' : 'chains'}. Only participating services are shown; individual service metrics are not simulated.` : 'All configured services are shown here. Their performance helps assess the full request chain; the deadline is recommended for that chain.';
  $('services').replaceChildren();
  const labels = {HEALTHY:'Healthy', STRESSED:'Stressed', OVERLOADED:'Overloaded', PENDING_BREACH:'Checking stress', LEARNING_BASELINE:'Learning baseline', BASELINE_READY:'Baseline ready', INSUFFICIENT_DATA:'Insufficient data'};
  for (const name of names) {
    const measurement = measurements.get(name), guard = guards.get(name);
    const card = el('article', undefined, 'service-card');
    card.append(el('h3', name.replace(/^mock-/, '').replaceAll('-', ' ')));
    const label = synthetic ? 'Example participant' : !collection?.enabled ? 'Collection off' : labels[guard?.health] || 'Waiting for measurements';
    const tone = guard?.health === 'HEALTHY' ? ' good' : ['STRESSED','OVERLOADED'].includes(guard?.health) ? ' bad' : ' neutral';
    card.append(el('span', label, 'badge' + tone));
    if (synthetic) card.append(el('p', 'Part of the synthetic request path.', 'muted'));
    else {
      const values = measurement?.available || {};
      card.append(el('p', `Service p95: ${ms(values.p95Ms)}`, 'muted'));
      card.append(el('p', `Requests in window: ${fmt(values.observedRequests)}`, 'muted'));
      if (measurement?.window.end) card.append(el('small', `Measured ${new Date(measurement.window.end).toLocaleString()}`, 'muted'));
    }
    $('services').append(card);
  }
  if (!names.size) $('services').append(el('p', synthetic ? 'Run an example to see its services.' : 'Service configuration will appear after the server is refreshed.', 'muted'));
}
function render() {
  $('live-tab').setAttribute('aria-selected', String(mode === 'live')); $('example-tab').setAttribute('aria-selected', String(mode === 'examples'));
  $('example-controls').hidden = mode !== 'examples'; $('run-example').disabled = demoBusy || !snapshot?.mockEnabled;
  $('source-status').className = 'source-status ' + (mode === 'examples' ? 'example' : snapshot?.collection.enabled ? 'live' : '');
  $('source-status').textContent = mode === 'examples' ? 'Synthetic data · safe to explore' : snapshot?.collection.enabled ? 'Live collection enabled' : 'Live collection is off';
  const data = entries(); const configuredView = mode === 'live' && data.length > 0 && data.every(item => item.configuredOnly);
  $('chain-count').textContent = `${data.length} ${configuredView ? (data.length === 1 ? 'deadline' : 'deadlines') : (data.length === 1 ? 'chain' : 'chains')}`; $('chains').replaceChildren();
  document.querySelector('.chain-panel h2').textContent = configuredView ? 'Configured deadlines' : 'Request chains';
  document.querySelector('.chain-panel > .muted').textContent = configuredView ? 'Current route deadline values scraped from the Component 2 gateway.' : 'A chain can include several services. Select a chain to see its end-to-end deadline.';
  document.querySelector('.workflow').hidden = configuredView;
  renderServices(data);
  if (!data.some(item => item.target === selected)) selected = data[0]?.target || null;
  if (!data.length) $('chains').append(el('p', mode === 'examples' ? 'Run an example to get started.' : 'No request chains yet.', 'muted'));
  for (const item of data) {
    const button = el('button', undefined, 'chain-button' + (item.target === selected ? ' selected' : ''));
    button.append(el('strong', route(item.target)), el('small', item.configuredOnly ? 'Current configured value' : mode === 'examples' ? 'Synthetic request chain' : item.assessment ? item.assessment.health.toLowerCase().replaceAll('_',' ') : 'Evidence received'));
    if (item.configuredOnly) button.append(el('small', ms(item.configuredDeadline.deadlineMs)));
    const proposal = currentChanges().find(change => change.proposal.target === item.target)?.proposal;
    if (proposal) button.append(el('small', `${ms(proposal.currentValue)} → ${ms(proposal.proposedValue)}`));
    button.append(el('small', item.measurement?.paths?.length ? `${item.measurement.paths.length} observed service ${item.measurement.paths.length === 1 ? 'path' : 'paths'} · one route deadline` : (item.target.split(' | ')[1] || '').replaceAll('mock-', '').replaceAll(' > ', ' → ')));
    button.setAttribute('aria-pressed', String(item.target === selected)); button.addEventListener('click', () => {selected = item.target; render();}); $('chains').append(button);
  }
  const item = data.find(d => d.target === selected); $('empty').hidden = !!item; $('result').hidden = !item;
  $('try-example').disabled = !snapshot?.mockEnabled;
  if (!item) {
    $('empty').querySelector('h2').textContent = mode === 'examples' ? 'Explore a sample recommendation' : 'No live evidence yet';
    $('empty').querySelector('p').textContent = mode === 'examples' ? !snapshot?.mockEnabled ? 'Synthetic examples are disabled. Restart the application with RRPE_MOCK_ENABLED=true to enable them.' : 'Choose a scenario and run the example. If it reports Unknown scenario, restart the application to load the updated backend.' : 'Check your server connections, or switch to Synthetic examples to explore a recommendation.';
  } else renderDetail(item);
}
function renderDetail(item) {
  const change = currentChanges().find(c => c.proposal.target === item.target), p = change?.proposal, a = item.assessment;
  const current = p?.currentValue ?? item.measurement?.currentDeadlineMs ?? item.configuredDeadline?.deadlineMs;
  const p99 = p?.measurements.recentP99Ms ?? a?.signals.p99 ?? item.measurement?.window.p99Ms;
  const baseline = p?.measurements.baselineP99Ms ?? a?.baseline.p99?.median;
  const unavailable = change && ['EXPIRED','SUPERSEDED','REJECTED'].includes(change.delivery === 'EXPIRED' || change.delivery === 'SUPERSEDED' ? change.delivery : change.decision);
  const proposed = unavailable ? null : p?.proposedValue;
  $('route-label').textContent = route(item.target); $('result-title').textContent = item.configuredOnly ? 'Configured gateway deadline' : 'Deadline recommendation';
  $('current-value').textContent = ms(current); $('p99-value').textContent = ms(p99); $('recommended-value').textContent = ms(proposed);
  $('recommended-caption').textContent = item.configuredOnly ? 'No recommendation from available metrics' : proposed == null ? 'Waiting for supported, current evidence' : mode === 'examples' ? 'Calculated from synthetic evidence' : 'Requires your review before application';
  $('decision-badge').textContent = item.configuredOnly ? 'Configured value' : mode === 'examples' ? 'Example result' : unavailable ? human[change.decision === 'REJECTED' ? 'REJECTED' : change.delivery] : change ? human[change.decision] : a?.health === 'LEARNING_BASELINE' || a?.health === 'BASELINE_READY' ? 'Learning baseline' : 'Waiting for evidence';
  $('decision-badge').className = 'badge' + (change?.delivery === 'APPLIED' ? ' good' : unavailable ? ' neutral' : '');
  $('path').replaceChildren();
  if (item.measurement?.paths?.length) {
    $('path').append(el('span', 'Latest observed service paths (visits in time order):', 'muted'));
    for (const path of item.measurement.paths) $('path').append(el('span', `${path.services.join(' → ')} · ${fmt(path.traceCount)} complete traces`, 'path-chip'));
  } else {
    const services = item.target.split(' | ')[1]?.split(' > ') || [];
    services.forEach((service,index) => { if(index) $('path').append(el('span','→')); $('path').append(el('span',service.replace(/^mock-/,'').replaceAll('-',' '),'path-chip')); });
  }
  const recovery = proposed != null && proposed < current;
  $('explanation-title').textContent = item.configuredOnly ? 'This is the deadline currently configured in Component 2.' : proposed != null ? recovery ? 'The chain has recovered. Lower its deadline gradually.' : 'Give this request chain more time to finish.' : 'We need more evidence before changing this deadline.';
  $('explanation').textContent = item.configuredOnly ? 'Prometheus provides the configured route budget, but the complete request and failure metrics needed to assess or recommend a change are unavailable. This value is shown for reference only.' : proposed != null ? recovery ? `Reduce the deadline from ${ms(current)} to ${ms(proposed)}. This recovery step preserves the healthy safety margin.` : `The measured request duration and persistent deadline failures support increasing the deadline from ${ms(current)} to ${ms(proposed)}.` : unavailable ? 'This decision has expired, was rejected, or no longer matches current evidence. A new recommendation is needed.' : a?.health === 'LEARNING_BASELINE' || a?.health === 'BASELINE_READY' ? 'The system is learning normal request duration and waiting for a separate recent window. It will recommend a change when enough evidence is available.' : 'A recommendation needs complete traces, enough requests and a reliable performance baseline.';
  $('facts').replaceChildren();
  const failureRate = p?.measurements.failureRate ?? a?.signals.deadlineFailureRate;
  if (Number.isFinite(baseline)) $('facts').append(el('span',`Healthy baseline: ${ms(baseline)}`));
  if (Number.isFinite(failureRate)) $('facts').append(el('span',`Deadline failure rate: ${fmt(failureRate*100)}%`));
  if (mode === 'examples') $('facts').append(el('span','No live changes'));
  renderEvidence(item, p, {current, p99, baseline, failureRate, proposed, recovery, unavailable});
  $('review-actions').replaceChildren();
  const eligible = mode === 'live' && change?.decision === 'PENDING_APPROVAL' && ['READY','WAITING_FOR_COMPONENT_3','FAILED'].includes(change.delivery) && Date.parse(p.expiresAt) > Date.now();
  if (eligible) { const button = el('button','Review deadline','button primary'); button.addEventListener('click', () => { reviewItem = change; $('review-summary').textContent = `${route(p.target)}: ${ms(p.currentValue)} → ${ms(p.proposedValue)}`; $('review-error').textContent=''; $('review-dialog').showModal(); }); $('review-actions').append(button); }
  else if (change?.decision === 'APPROVED') $('review-actions').append(el('p',human[change.delivery] || 'Approval recorded.','review-note'));
}
function renderEvidence(item, proposal, values) {
  const {current, p99, baseline, failureRate, proposed, recovery, unavailable} = values;
  const measurements = proposal?.measurements;
  // Keep proposal evidence tied to its own measurement window, rather than newer collection data.
  const requests = proposal ? measurements?.requestCount : item.measurement?.window.requests;
  const failures = proposal ? measurements?.deadlineFailureCount : item.measurement?.window.deadlineFailures;
  const container = $('evidence'); container.replaceChildren();
  function add(title, text, tone = 'neutral') {
    const card = el('div', undefined, 'evidence-item ' + tone);
    card.append(el('span', tone === 'warning' ? '!' : tone === 'good' ? '✓' : 'i', 'evidence-icon'));
    const content = el('div'); content.append(el('h3', title), el('p', text)); card.append(content); container.append(card);
  }
  if (unavailable) add('Earlier evidence', 'These measurements supported an earlier decision. They do not support applying a deadline change now.');
  const coverage = proposal ? measurements : item.measurement?.coverage && {
    completeTraceCount:item.measurement.coverage.traceCount, prometheusRequestCount:item.measurement.coverage.prometheusCount,
    traceCountTolerance:item.measurement.coverage.tolerance, coverageAccepted:item.measurement.coverage.accepted ? 1 : 0
  };
  if (Number.isFinite(coverage?.completeTraceCount) && Number.isFinite(coverage?.prometheusRequestCount) && Number.isFinite(coverage?.traceCountTolerance)) {
    add('Trace coverage', `${fmt(coverage.completeTraceCount)} complete Tempo traces compared with ${fmt(coverage.prometheusRequestCount)} estimated completed requests in Prometheus. Allowed difference: ${fmt(coverage.traceCountTolerance)} requests. ${coverage.coverageAccepted === 1 ? 'Coverage checks passed.' : 'Evidence is incomplete; a deadline change is blocked.'}`, coverage.coverageAccepted === 1 ? 'good' : 'warning');
  }
  if (Number.isFinite(measurements?.observedPathCount) && measurements.observedPathCount > 0) {
    add('One deadline for this route', `The recommendation uses complete request durations across ${fmt(measurements.observedPathCount)} observed service paths. Route counts and failures are counted once.`);
    for (const line of proposal.evidence || []) if (line.startsWith('Observed path (')) add('Path supporting this recommendation', line);
  }
  if (Number.isFinite(failures) && Number.isFinite(requests) && requests > 0) {
    add(failures > 0 ? 'Requests exceeded the deadline' : 'No deadline failures in this window',
      `${fmt(failures)} of ${fmt(requests)} requests exceeded their deadline (${fmt(failures / requests * 100)}%).`, failures > 0 ? 'warning' : 'good');
  } else if (Number.isFinite(failureRate)) {
    add('Deadline failure rate', `${fmt(failureRate * 100)}% of measured requests exceeded their deadline. Request counts are not available in this result.`, failureRate > 0 ? 'warning' : 'good');
  }
  if (Number.isFinite(p99) && Number.isFinite(current)) {
    const difference = p99 - current;
    add(difference > 0 ? 'Measured p99 is above the deadline' : 'Measured p99 fits within the deadline',
      `Chain p99 is ${ms(p99)}, ${difference === 0 ? 'equal to' : `${ms(Math.abs(difference))} ${difference > 0 ? 'above' : 'below'}`} the ${ms(current)} deadline. P99 describes the measured duration, not the number of deadline failures.`, difference > 0 ? 'warning' : 'good');
  }
  if (Number.isFinite(p99) && Number.isFinite(baseline) && baseline > 0) {
    const change = (p99 / baseline - 1) * 100;
    add('Compared with normal performance', change === 0 ? `Chain p99 matches the healthy baseline of ${ms(baseline)}.` : `Chain p99 is ${fmt(Math.abs(change))}% ${change > 0 ? 'higher' : 'lower'} than the healthy baseline of ${ms(baseline)}.`, change > 0 ? 'warning' : 'good');
  }
  if (proposed != null && recovery && Number.isFinite(measurements?.healthyCycles)) {
    add('Recovery persisted', `The chain stayed healthy for ${fmt(measurements.healthyCycles)} consecutive analysis cycles, supporting a gradual deadline reduction.`, 'good');
  } else if (proposed != null && !recovery && Number.isFinite(measurements?.failureCycles)) {
    add('Failures persisted', `Deadline failures remained above the healthy baseline for ${fmt(measurements.failureCycles)} consecutive analysis cycles.`, 'warning');
  }
  if (!proposal) {
    const messages = [...(item.assessment?.evidence || []), ...(item.measurement?.problems || [])].filter(line => typeof line === 'string' && line.trim());
    for (const message of new Set(messages)) add('Evidence status', message);
  }
  if (item.configuredOnly) add('Source: Component 2 Prometheus', `Configured gateway budget: ${ms(current)}. Scraped at ${new Date(item.configuredDeadline.observedAt).toLocaleString()}. No deadline adjustment is being proposed.`);
  if (!container.children.length && !item.configuredOnly) add('Waiting for measurements', 'No measured deadline evidence is available yet. Check the Prometheus and Tempo connections.');
}
async function refresh() {
  if (busy) return; busy=true; $('refresh').disabled=true;
  try { snapshot=await api(); if(initial) { initial=false; if(snapshot.mockEnabled && !snapshot.collection.chains?.length && !snapshot.collection.configuredDeadlines?.length) {mode='examples'; await runExample();} } render(); }
  catch(e) { error(e.message); } finally {busy=false; $('refresh').disabled=false;}
}
async function runExample() {
  if(demoBusy || !snapshot?.mockEnabled) return; demoBusy=true; $('run-example').disabled=true; $('feedback').hidden=true;
  try { example=await api('/demo/'+$('scenario').value,{}); selected=null; }
  catch(e) {example=null; selected=null; error(e.message === 'Unknown scenario' ? 'This running backend does not support the selected example yet. Restart the application, refresh this page, and run the example again.' : e.message);} finally {demoBusy=false; render();}
}
$('live-tab').addEventListener('click',()=>setMode('live')); $('example-tab').addEventListener('click',()=>setMode('examples'));
$('try-example').addEventListener('click',()=>setMode('examples')); $('refresh').addEventListener('click',refresh); $('run-example').addEventListener('click',runExample);
$('connections').addEventListener('click',()=>{$('connection-dialog').showModal();});
for(const button of document.querySelectorAll('[data-close]')) button.addEventListener('click',()=>$(button.dataset.close).close());
$('check-connections').addEventListener('click',async()=>{
  $('check-connections').disabled=true; $('connection-results').textContent='Checking your configured servers…';
  try {const result=await api('/connections'); $('connection-results').replaceChildren(); for(const [name,item] of Object.entries(result)) {const card=el('div',undefined,'connection-card'); card.append(el('strong',name==='prometheus'?'Prometheus':'Tempo'),el('p',item.state==='LIVE_DATA'?'Connected · recent data found':item.state==='NO_FRESH_DATA'?'Connected · waiting for recent data':'Cannot connect'),el('p',item.detail)); $('connection-results').append(card);} }
  catch(e) {$('connection-results').textContent=e.message;} finally {$('check-connections').disabled=false;}
});
$('review-form').addEventListener('submit',async event=>{
  event.preventDefault(); if(!reviewItem) return; const buttons=[...$('review-form').querySelectorAll('button[type=submit]')]; buttons.forEach(b=>b.disabled=true);
  try {await api(`/changes/${reviewItem.proposal.id}/review`,{reviewer:$('reviewer').value,approve:event.submitter?.value==='approve',comment:null}); $('review-dialog').close(); $('token').value=''; await refresh();}
  catch(e) {$('review-error').textContent=e.message;} finally {buttons.forEach(b=>b.disabled=false);}
});
refresh(); setInterval(refresh,30000);
