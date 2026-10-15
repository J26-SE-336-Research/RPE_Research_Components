'use strict';

const byId = id => document.getElementById(id);
let reviews = [];
let selectedId = null;
let decisionBusy = false;
let decisionError = null;
let windowSeconds = 300;
let refreshBusy = false;
let learnedAnalyses = [];
const suppliedAnalyses = new Map();
const chainAnalyses = new Map();
let selectedService = null;
let selectedChain = null;
let recoveryWindows = 2;
let simulatedRecoveryEvents = [];
const pages = { overview: 'Intelligence Overview', services: 'Service Health', chains: 'Request Chains',
  decisions: 'Policy Decisions', recovery: 'Recovery & History' };

function showPage(id) {
  if (!pages[id]) return;
  document.querySelectorAll('.page').forEach(page => page.classList.toggle('active', page.id === id));
  document.querySelectorAll('[data-page]').forEach(button => {
    button.classList.toggle('active', button.dataset.page === id);
    button.setAttribute('aria-current', button.dataset.page === id ? 'page' : 'false');
  });
  byId('topTitle').textContent = pages[id];
  window.scrollTo({ top: 0, behavior: 'smooth' });
}
document.querySelectorAll('[data-page]').forEach(button => button.addEventListener('click', () => showPage(button.dataset.page)));
document.querySelectorAll('[data-goto]').forEach(button => button.addEventListener('click', () => showPage(button.dataset.goto)));

function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}

function feedback(message, error = false) {
  const element = byId('feedback');
  element.textContent = message;
  element.className = error ? 'feedback error' : 'feedback';
  element.hidden = false;
}

async function request(url, body) {
  let response;
  try {
    response = await fetch(url, { method: body === undefined ? 'GET' : 'POST',
      headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(15000) });
  } catch (error) {
    throw new Error('Cannot reach the intelligence service. Check that it is running, then try again.');
  }
  const content = await response.json().catch(() => null);
  if (!response.ok) throw new Error(content?.detail || content?.message || `Request failed (HTTP ${response.status}).`);
  if (content === null) throw new Error('The service returned an unreadable response.');
  return content;
}

function planOf(review) { return review.servicePlan || review.deadlinePlan; }
function expiryOf(review) { return Date.parse(planOf(review).expiresAt); }
function stateOf(review) {
  return ['PENDING', 'APPROVED', 'AUTO_APPROVED'].includes(review.status) && expiryOf(review) <= Date.now() ? 'EXPIRED' : review.status;
}
function timeLeft(review) {
  const seconds = Math.max(0, Math.ceil((expiryOf(review) - Date.now()) / 1000));
  if (stateOf(review) === 'EXPIRED') return 'Validity period ended';
  if (review.status === 'REJECTED') return 'Review closed';
  return `Valid for ${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}
function date(value) { return value ? new Date(value).toLocaleString() : '—'; }
function targetOf(review) { return review.servicePlan?.serviceName || review.deadlinePlan.chainId; }

function actionDescription(action, parameters) {
  if (action === 'RATE_LIMIT') return `Admit at most ${parameters.maxRequestsPerSecond} requests per second.`;
  if (action === 'DISABLE_RETRIES') return 'Allow the initial attempt with zero extra retries.';
  if (action === 'RETRY_BUDGET') return `Allow at most ${parameters.retryBudgetRatio * 100} extra retry attempts per 100 original requests, across the service.`;
  if (action === 'CHANGE_CHAIN_DEADLINE') return `Set the chain deadline to ${parameters.proposedDeadlineMs} ms.`;
  return 'Unsupported action. Do not approve this plan.';
}
function actionsOf(review) {
  return review.servicePlan ? review.servicePlan.recommendations : [{ action: review.deadlinePlan.action,
    parameters: { proposedDeadlineMs: review.deadlinePlan.proposedDeadlineMs }, reason: review.deadlinePlan.message }];
}
function supports(review) {
  const plan = planOf(review);
  return plan.schemaVersion === '1.0' && plan.mode === 'ADVISORY' && actionsOf(review).length > 0 &&
    actionsOf(review).every(item => ['RATE_LIMIT', 'DISABLE_RETRIES', 'RETRY_BUDGET', 'CHANGE_CHAIN_DEADLINE'].includes(item.action));
}

function renderReviews() {
  const list = byId('review-list');
  list.replaceChildren();
  const visible = reviews.filter(review => byId('status-filter').value === 'ALL' || stateOf(review) === byId('status-filter').value);
  if (!visible.length) list.append(node('p', reviews.length ? 'No reviews match this filter.' : 'Your queue is empty. Analyze learned metrics or add synthetic demo plans below.', 'empty'));
  for (const review of visible) {
    const card = node('article', undefined, 'review-card');
    card.dataset.reviewId = review.recommendationId;
    const info = node('div');
    const heading = node('div', undefined, 'card-heading');
    heading.append(node('h3', targetOf(review)), node('span', stateOf(review), `badge ${stateOf(review)}`));
    info.append(heading);
    const health = review.servicePlan?.status || review.deadlinePlan.evidence.chainHealth;
    info.append(node('p', `${review.servicePlan ? 'Service policy' : 'Chain deadline'} · ${health}`, 'health'));
    info.append(node('p', actionsOf(review).map(item => actionDescription(item.action, item.parameters)).join(' '), 'card-meta'));
    info.append(node('p', `Created ${date(planOf(review).generatedAt)}${review.reviewer ? ` · Reviewed by ${review.reviewer}` : ''}`, 'card-meta'));
    const controls = node('div', undefined, 'card-actions');
    const button = node('button', stateOf(review) === 'PENDING' ? 'Review plan' : 'View details', 'btn small primary');
    button.type = 'button';
    button.addEventListener('click', () => openReview(review.recommendationId));
    controls.append(node('span', timeLeft(review), 'countdown'), button);
    card.append(info, controls);
    list.append(card);
  }
  tick();
  renderIntelligence();
}

async function refresh() {
  if (refreshBusy) return;
  refreshBusy = true;
  byId('refresh').disabled = true;
  try {
    const results = await Promise.all([request('/api/v1/reviews'), request('/api/v1/metrics')]);
    reviews = results[0];
    learnedAnalyses = results[1];
    renderReviews();
    byId('last-refresh').textContent = `Updated ${new Date().toLocaleTimeString()}`;
    if (selectedId && byId('review-dialog').open && !decisionBusy) renderDialog();
  } finally {
    refreshBusy = false;
    byId('refresh').disabled = false;
  }
}

function tick() {
  const counts = { PENDING: 0, APPROVED: 0, AUTO_APPROVED: 0, REJECTED: 0, EXPIRED: 0 };
  for (const review of reviews) counts[stateOf(review)]++;
  for (const [state, count] of Object.entries(counts)) byId(`count-${state}`).textContent = count;
  byId('pendingBadge').textContent = `${counts.PENDING} pending`;
  for (const card of document.querySelectorAll('[data-review-id]')) {
    const review = reviews.find(item => item.recommendationId === card.dataset.reviewId);
    if (!review) continue;
    const badge = card.querySelector('.badge');
    badge.textContent = stateOf(review);
    badge.className = `badge ${stateOf(review)}`;
    card.querySelector('.countdown').textContent = timeLeft(review);
    card.querySelector('button').textContent = stateOf(review) === 'PENDING' ? 'Review plan' : 'View details';
  }
  const selected = reviews.find(item => item.recommendationId === selectedId);
  if (selected && byId('review-dialog').open) {
    const pending = !!selected.deadlinePlan && stateOf(selected) === 'PENDING';
    byId('decision-form').hidden = !selected.deadlinePlan;
    byId('approve').disabled = decisionBusy || !pending || !supports(selected);
    byId('reject').disabled = decisionBusy || !pending;
    byId('reviewer').disabled = decisionBusy || !pending;
    byId('review-comment').disabled = decisionBusy || !pending;
    byId('decision-status').textContent = decisionBusy ? 'Recording decision…' : (decisionError ||
      (stateOf(selected) === 'EXPIRED' ? 'Expired. Generate a new plan to review.' :
      !supports(selected) ? 'This plan contains an unsupported action or schema.' : timeLeft(selected)));
  }
}

function detail(label, value) {
  const item = node('div');
  item.append(node('span', label), node('strong', value));
  return item;
}
function renderDialog() {
  const review = reviews.find(item => item.recommendationId === selectedId);
  if (!review) return;
  const plan = planOf(review);
  byId('dialog-title').textContent = targetOf(review);
  const content = byId('dialog-content');
  content.replaceChildren(node('span', stateOf(review), `badge ${stateOf(review)}`));
  const grid = node('div', undefined, 'detail-grid');
  grid.append(detail('Generated', date(plan.generatedAt)), detail('Original expiry', date(plan.expiresAt)),
    detail('Source window end', date(review.servicePlan?.sourceCollectedAt || review.deadlinePlan.evidence.windowEnd)),
    detail('Review identifier', review.recommendationId));
  content.append(grid);
  for (const item of actionsOf(review)) {
    const action = node('div', undefined, 'plan-action');
    action.append(node('strong', item.action), node('p', actionDescription(item.action, item.parameters)), node('p', item.reason, 'muted'));
    content.append(action);
  }
  const evidence = node('div', undefined, 'detail-section');
  evidence.append(node('h3', 'Evidence'));
  if (review.servicePlan) {
    const list = node('ul', undefined, 'evidence-list');
    for (const text of plan.evidence) list.append(node('li', text));
    evidence.append(list);
  } else evidence.append(node('pre', JSON.stringify(plan.evidence, null, 2)));
  content.append(evidence);
  if (review.reviewedAt) {
    const audit = node('div', undefined, 'detail-section');
    audit.append(node('h3', 'Decision history'), node('p', `${review.reviewer} · ${date(review.reviewedAt)}`, 'muted'));
    if (review.comment) audit.append(node('p', review.comment));
    content.append(audit);
  }
  tick();
}
function openReview(id) {
    selectedId = id;
    decisionError = null;
  byId('review-comment').value = '';
  byId('review-dialog').showModal();
  renderDialog();
}

byId('close-dialog').addEventListener('click', () => byId('review-dialog').close());
byId('status-filter').addEventListener('change', renderReviews);
byId('refresh').addEventListener('click', () => refresh().catch(error => feedback(error.message, true)));
byId('decision-form').addEventListener('submit', async event => {
  event.preventDefault();
  const decision = event.submitter?.value;
  const review = reviews.find(item => item.recommendationId === selectedId);
  if (decisionBusy || !review || stateOf(review) !== 'PENDING' || !['APPROVE', 'REJECT'].includes(decision)) return;
  decisionBusy = true;
  decisionError = null;
  tick();
  try {
    const updated = await request('/api/v1/reviews/decisions', { recommendationId: selectedId, decision,
      reviewer: byId('reviewer').value, comment: byId('review-comment').value || null });
    reviews = reviews.map(item => item.recommendationId === updated.recommendationId ? updated : item);
    feedback(updated.status === 'EXPIRED' ? 'The plan expired. Generate a fresh recommendation.' :
      updated.status === 'APPROVED' ? 'Approval recorded. No policy was sent or applied.' : 'Rejection recorded.');
    byId('review-dialog').close();
    renderReviews();
  } catch (error) {
    decisionError = error.message;
    feedback(error.message, true);
    await refresh().catch(() => {});
  } finally { decisionBusy = false; tick(); }
});

async function runForm(form, action) {
  const buttons = [...form.querySelectorAll('button')];
  buttons.forEach(button => { button.disabled = true; });
  try { await action(); } catch (error) { feedback(error.message, true); }
  finally { buttons.forEach(button => { button.disabled = false; }); }
}
byId('service-form').addEventListener('submit', event => {
  event.preventDefault();
  runForm(event.currentTarget, async () => {
    const service = byId('service-name').value.trim();
    if (!service) throw new Error('Enter a service name.');
    const result = await request(`/api/v1/metrics/${encodeURIComponent(service)}/reviews`, {});
    suppliedAnalyses.set(service, { ...result.analysis, sourceType: 'Learned observations' });
    feedback(result.message);
    await refresh();
  });
});

byId('demo').addEventListener('click', async () => {
  byId('demo').disabled = true;
  try {
    for (const overloaded of [false, true]) {
      const serviceName = `synthetic-${overloaded ? 'overloaded' : 'stressed'}-${crypto.randomUUID().slice(0, 8)}`;
      const baseline = { serviceName, collectedAt: new Date(Date.now() - 360000).toISOString(), requestRate: 100,
        p95LatencyMs: 100, errorRate: 0, retryCount: 0, rejectedRequestCount: 0, deadlineFailureCount: 0,
        cancellationCount: 0, inFlightRequests: 20, successfulThroughput: 100 };
      const recent = { ...baseline,
        collectedAt: new Date(Date.now() - 1000).toISOString(), requestRate: overloaded ? 150 : 120,
        p95LatencyMs: overloaded ? 250 : 160, errorRate: overloaded ? 0.12 : 0.06, retryCount: 30 };
      const result = await request('/api/v1/reviews/service', { baseline, recent });
      suppliedAnalyses.set(serviceName, { ...result.analysis, ready: true, sourceType: 'Synthetic supplied snapshots',
        recentWindow: { summary: recent }, baselineLearning: { baseline } });
    }
    feedback('Two synthetic service decisions recorded automatically. Demo plans are excluded from live policy delivery.');
    await refresh();
  } catch (error) { feedback(error.message, true); }
  finally { byId('demo').disabled = false; }
});

function exampleWindow() {
  const end = Date.now() - 1000;
  return { windowStart: new Date(end - windowSeconds * 1000).toISOString(), windowEnd: new Date(end).toISOString() };
}
byId('deadline-example').addEventListener('click', () => {
  byId('deadline-input').value = JSON.stringify({ chainId: 'synthetic-order-payment', services: ['order-service', 'payment-service'],
    chainHealth: 'STRESSED', ...exampleWindow(), requestCount: 1000, deadlineFailureCount: 20,
    p99LatencyMs: 1200, currentDeadlineMs: 1000 }, null, 2);
  feedback('Synthetic deadline evidence loaded. Click Analyze deadline to generate a reviewable plan.');
});
byId('deadline-form').addEventListener('submit', event => {
  event.preventDefault();
  runForm(event.currentTarget, async () => {
    const result = await request('/api/v1/reviews/deadline', JSON.parse(byId('deadline-input').value));
    chainAnalyses.set(result.analysis.chainId, { plan: result.analysis, review: result.review });
    selectedChain = result.analysis.chainId;
    feedback(result.review ? 'Chain deadline suggestion saved for review.' : result.message);
    await refresh();
  });
});
byId('cancellation-example').addEventListener('click', () => {
  byId('cancellation-input').value = JSON.stringify({ chainId: 'synthetic-cancellation-chain', services: ['order-service', 'payment-service'],
    ...exampleWindow(), cancellationCount: 20, continuedProcessingCount: 15, downstreamActivityCount: 12,
    observationGraceMs: 100, telemetryComplete: true }, null, 2);
  feedback('Synthetic cancellation evidence loaded. It is not live trace data.');
});
byId('cancellation-form').addEventListener('submit', event => {
  event.preventDefault();
  runForm(event.currentTarget, async () => {
    const result = await request('/api/v1/analyses/cancellations', JSON.parse(byId('cancellation-input').value));
    const container = byId('cancellation-result');
    container.replaceChildren(node('h3', result.chainId), node('p', result.status, 'health'), node('p', result.message));
    for (const finding of result.findings) {
      const card = node('div', undefined, 'plan-action');
      card.append(node('strong', `${finding.code} · ${finding.affectedRequestCount} cancelled requests`),
        node('p', finding.message), node('p', finding.suggestedCheck, 'muted'));
      container.append(card);
    }
    const details = node('details', undefined, 'input-details');
    details.append(node('summary', 'Source evidence'), node('pre', JSON.stringify(result.evidence, null, 2)));
    container.append(details);
    feedback('Cancellation analysis completed. Findings do not change any policy.');
  });
});

function stateBadge(status) {
  const severity = status === 'HEALTHY' || status === 'APPROVED' ? 'healthy' :
    status === 'OVERLOADED' || status === 'REJECTED' ? 'overloaded' : 'stressed';
  return node('span', status.replaceAll('_', ' '), `state ${severity}`);
}
function numeric(value, suffix = '') {
  return Number.isFinite(value) ? `${Number(value.toFixed(2)).toLocaleString()}${suffix}` : '—';
}
function percent(value) { return Number.isFinite(value) ? `${(value * 100).toFixed(2)}%` : '—'; }
function fact(label, value) {
  const item = node('div', undefined, 'fact');
  item.append(node('span', label), node('strong', value));
  return item;
}
function serviceData() {
  const data = new Map(suppliedAnalyses);
  for (const analysis of learnedAnalyses) data.set(analysis.serviceName, { ...analysis, sourceType: 'Learned observations' });
  for (const review of reviews) {
    if (review.servicePlan && !data.has(review.servicePlan.serviceName)) {
      data.set(review.servicePlan.serviceName, { ...review.servicePlan, ready: true, sourceType: 'Recorded review snapshot' });
    }
  }
  const rank = { OVERLOADED: 0, STRESSED: 1, HEALTHY: 2 };
  return [...data.values()].sort((a, b) => (rank[a.status] ?? 3) - (rank[b.status] ?? 3) || a.serviceName.localeCompare(b.serviceName));
}
function reasonList(container, analysis) {
  container.replaceChildren();
  const reasons = analysis.evidence?.length ? analysis.evidence : [analysis.message || 'No classification evidence available.'];
  for (const text of reasons) {
    const item = node('div');
    item.append(node('b', text));
    container.append(item);
  }
}
function renderIntelligence() {
  const services = serviceData();
  const featured = services[0];
  if (featured) {
    byId('overview-state').replaceWith(Object.assign(stateBadge(featured.status), { id: 'overview-state' }));
    byId('overview-source').textContent = featured.sourceType;
    byId('overview-title').textContent = featured.ready ? `${featured.serviceName} is ${featured.status.toLowerCase()}` : `Learning about ${featured.serviceName}`;
    byId('overview-description').textContent = `${featured.sourceType} · ${date(featured.sourceCollectedAt || featured.recentWindow?.windowEnd)}. ${featured.message || 'Classification describes the supplied snapshot; it does not prove current conditions or a chain bottleneck.'}`;
    const recent = featured.recentWindow?.summary;
    const baseline = featured.baselineLearning?.baseline;
    byId('metric-latency').textContent = numeric(recent?.p95LatencyMs, ' ms');
    byId('metric-errors').textContent = percent(recent?.errorRate);
    byId('metric-retries').textContent = numeric(recent?.retryCount);
    byId('metric-latency-note').textContent = featured.sourceType === 'Learned observations' ? 'Maximum observed p95 in the recent window.' : 'Supplied snapshot p95; not a measured p99.';
    byId('metric-baseline').textContent = `Baseline observed p95: ${numeric(baseline?.p95LatencyMs, ' ms')}`;
    byId('metric-error-baseline').textContent = `Baseline error rate: ${percent(baseline?.errorRate)}`;
    byId('metric-retry-baseline').textContent = `Baseline retry count: ${numeric(baseline?.retryCount)}`;
    reasonList(byId('overview-evidence'), featured);
    const response = byId('overview-decisions');
    response.replaceChildren();
    for (const action of featured.recommendations || []) {
      const item = node('div', undefined, 'decision');
      const description = node('div');
      description.append(node('h4', action.action.replaceAll('_', ' ')), node('p', action.reason));
      item.append(description, node('div', actionDescription(action.action, action.parameters), 'change'));
      response.append(item);
    }
    if (!response.children.length) response.append(node('p', 'No actionable policy change for this evidence.', 'empty'));
    byId('next-action-title').textContent = featured.ready ? 'Review the evidence and proposed changes' : 'Allow the system to collect enough observations';
    const next = byId('next-action-list');
    next.replaceChildren(node('li', featured.ready ? 'Service controls update automatically; open Policy Decisions for their history. Chain deadlines require approval.' : featured.message),
      node('li', 'Approval preserves the original expiry.'), node('li', 'Distribution and recovery verification are not connected yet.'));
  }
  byId('service-count').textContent = `${services.length} services`;
  const table = byId('service-table');
  table.replaceChildren();
  if (!services.length) {
    const row = node('tr'), cell = node('td', 'No service evidence yet. Submit observations or load synthetic examples.');
    cell.colSpan = 6; row.append(cell); table.append(row);
  }
  if (!services.some(service => service.serviceName === selectedService)) selectedService = services[0]?.serviceName;
  for (const service of services) {
    const metrics = service.recentWindow?.summary;
    const row = node('tr', undefined, `selectable${service.serviceName === selectedService ? ' selected' : ''}`);
    const name = node('td'), button = node('button', service.serviceName, 'service-select');
    button.type = 'button';
    button.addEventListener('click', () => { selectedService = service.serviceName; renderIntelligence(); });
    name.append(button);
    const state = node('td'); state.append(stateBadge(service.status));
    row.append(name, state, node('td', numeric(metrics?.p95LatencyMs, ' ms')), node('td', percent(metrics?.errorRate)),
      node('td', numeric(metrics?.retryCount)), node('td', service.sourceType));
    table.append(row);
  }
  const selected = services.find(service => service.serviceName === selectedService);
  if (selected) {
    byId('serviceTitle').textContent = `${selected.serviceName} explanation`;
    byId('serviceSubtitle').textContent = `${selected.sourceType} · ${date(selected.sourceCollectedAt || selected.recentWindow?.windowEnd)}`;
    byId('serviceState').replaceWith(Object.assign(stateBadge(selected.status), { id: 'serviceState' }));
    reasonList(byId('serviceReasons'), selected);
    const recent = selected.recentWindow?.summary, baseline = selected.baselineLearning?.baseline;
    byId('service-facts').replaceChildren(fact('Recent request rate', numeric(recent?.requestRate, ' req/s')),
      fact('Baseline request rate', numeric(baseline?.requestRate, ' req/s')),
      fact('Baseline learning', selected.baselineLearning?.phase || 'Supplied snapshot'),
      fact('Recent samples', numeric(selected.recentWindow?.sampleCount)));
  }
  renderChains();
  renderHistory();
}
function availableChains() {
  const chains = new Map(chainAnalyses);
  for (const review of reviews) {
    if (review.deadlinePlan && !chains.has(review.deadlinePlan.chainId)) chains.set(review.deadlinePlan.chainId, { plan: review.deadlinePlan, review });
  }
  return [...chains.values()];
}
function renderChains() {
  const chains = availableChains();
  byId('chain-count').textContent = `${chains.length} supplied chains`;
  if (!chains.some(chain => chain.plan.chainId === selectedChain)) selectedChain = chains[0]?.plan.chainId;
  const list = byId('chain-list');
  list.replaceChildren();
  if (!chains.length) list.append(node('p', 'No chain evidence submitted yet.', 'empty'));
  for (const chain of chains) {
    const button = node('button', undefined, `chain-item${chain.plan.chainId === selectedChain ? ' active' : ''}`);
    button.type = 'button';
    button.append(node('strong', chain.plan.chainId), node('span', chain.plan.evidence.services.join(' → ')),
      node('small', `Chain p99 ${numeric(chain.plan.evidence.p99LatencyMs, ' ms')}`));
    button.addEventListener('click', () => { selectedChain = chain.plan.chainId; renderChains(); });
    list.append(button);
  }
  const selected = chains.find(chain => chain.plan.chainId === selectedChain);
  if (!selected) return;
  const plan = selected.plan, evidence = plan.evidence;
  byId('chainTitle').textContent = plan.chainId;
  byId('chainSubtitle').textContent = `${numeric(evidence.requestCount)} requests · window ended ${date(evidence.windowEnd)} · caller-supplied evidence`;
  byId('chainState').replaceWith(Object.assign(stateBadge(selected.review ? stateOf(selected.review) : plan.status), { id: 'chainState' }));
  const path = byId('chainPath'); path.replaceChildren();
  evidence.services.forEach((service, index) => {
    if (index) path.append(node('span', '→', 'arrow'));
    const hop = node('div', undefined, 'hop');
    hop.append(node('i', String(index + 1)), node('b', service), node('small', 'Timing unavailable'));
    path.append(hop);
  });
  byId('chainFacts').replaceChildren(fact('End-to-end p99', numeric(evidence.p99LatencyMs, ' ms')),
    fact('Current deadline', numeric(evidence.currentDeadlineMs, ' ms')), fact('Deadline failures', numeric(evidence.deadlineFailureCount)),
    fact('Suggested deadline', numeric(plan.proposedDeadlineMs, ' ms')));
  byId('chainFinding').textContent = `${plan.message} The service path is supplied by the caller. Per-hop timings and automatic chain discovery are not available yet.`;
}
function renderHistory() {
  const body = byId('historyBody'); body.replaceChildren();
  const history = reviews.filter(review => review.reviewedAt || stateOf(review) === 'EXPIRED');
  if (!history.length && !byId('showDemoHistory').checked) {
    const row = node('tr'), cell = node('td', 'No review decisions recorded yet.');
    cell.colSpan = 5; row.append(cell); body.append(row);
  }
  for (const review of history) {
    const status = stateOf(review), row = node('tr');
    const plan = planOf(review);
    const summary = actionsOf(review).map(action => actionDescription(action.action, action.parameters)).join(' ');
    const evidence = review.servicePlan ? plan.evidence.join(' · ') :
      `Chain p99 ${numeric(plan.evidence.p99LatencyMs, ' ms')} · ${numeric(plan.evidence.requestCount)} requests`;
    const result = node('td'); result.append(stateBadge(status));
    const decision = node('td', summary);
    decision.append(node('small', review.reviewer ? `Reviewed by ${review.reviewer}` : 'No review decision', 'audit-reviewer'));
    row.append(node('td', date(review.reviewedAt || planOf(review).expiresAt)), node('td', targetOf(review)),
      decision, node('td', evidence), result);
    body.append(row);
  }
  if (byId('showDemoHistory').checked) {
    const examples = [
      ...simulatedRecoveryEvents,
      { time: '10:42:18', target: 'Service C', decision: 'Retry budget 3 → 2', evidence: 'Retry amplification 1.5×', result: 'Enforced', severity: 'healthy' },
      { time: '10:31:06', target: 'CH-01', decision: 'Deadline 1,200 → 1,500 ms', evidence: 'p99 1.10 s', result: 'Approved', severity: 'healthy' },
      { time: '10:18:44', target: 'Service B', decision: 'Leak rate restoration', evidence: 'Healthy windows 2/5', result: 'Recovering', severity: 'stressed' }
    ];
    for (const example of examples) {
      const row = node('tr', undefined, 'demo-history-row');
      const target = node('td', example.target); target.append(node('small', 'Synthetic demo', 'audit-reviewer'));
      const result = node('td'); result.append(node('span', `${example.result} (demo)`, `state ${example.severity}`));
      row.append(node('td', example.time), target, node('td', example.decision), node('td', example.evidence), result);
      body.append(row);
    }
  }
}

function renderRecovery() {
  const ready = recoveryWindows >= 5;
  byId('windowCount').textContent = recoveryWindows;
  byId('recoveryBar').style.width = `${recoveryWindows / 5 * 100}%`;
  byId('recoveryProgress').setAttribute('aria-valuenow', String(recoveryWindows));
  byId('recoveryState').textContent = ready ? 'READY TO RESTORE' : 'RECOVERING';
  byId('recoveryState').className = `state ${ready ? 'healthy' : 'stressed'}`;
  const remaining = 5 - recoveryWindows;
  byId('nextRecovery').textContent = ready ? 'Begin gradual leak-rate restoration' :
    `Wait for ${remaining} more healthy window${remaining === 1 ? '' : 's'}`;
  byId('restoreText').textContent = ready ?
    'Demo guard completed. A connected implementation would restore one controlled step at a time while monitoring recovery.' :
    'Then restore the leak rate gradually: 84 → 92 → 101 → 110 → 120 requests/s.';
  byId('addWindow').disabled = ready;
  byId('resetRecovery').hidden = !ready;
}
byId('addWindow').addEventListener('click', () => {
  if (recoveryWindows >= 5) return;
  recoveryWindows++;
  simulatedRecoveryEvents.unshift({ time: new Date().toLocaleTimeString(), target: 'Service B',
    decision: recoveryWindows === 5 ? 'Recovery guard completed' : 'Healthy window simulated',
    evidence: `${recoveryWindows}/5 simulated healthy windows`, result: recoveryWindows === 5 ? 'Ready' : 'Recovering',
    severity: recoveryWindows === 5 ? 'healthy' : 'stressed' });
  renderRecovery(); renderHistory();
  feedback('Synthetic healthy window recorded in this browser only. No policy was changed.');
});
byId('resetRecovery').addEventListener('click', () => {
  recoveryWindows = 2; simulatedRecoveryEvents = []; renderRecovery(); renderHistory();
});
byId('showDemoHistory').addEventListener('change', renderHistory);
renderRecovery();

(async () => {
  try {
    const config = await request('/api/v1/reviews/config');
    windowSeconds = config.recentWindowSeconds;
    byId('window-label').textContent = `Advisory analysis · ${windowSeconds % 60 === 0 ? `${windowSeconds / 60}-minute` : `${windowSeconds}-second`} window`;
    await refresh();
  } catch (error) { feedback(error.message, true); byId('last-refresh').textContent = 'Connection unavailable'; }
})();
setInterval(tick, 1000);
setInterval(() => { if (!decisionBusy) refresh().catch(error => feedback(error.message, true)); }, 20000);
