'use strict';
/*
 * Xiaoman web API (same origin; no external dependencies):
 * GET /api/info {name,version,accessCodeRequired,classTokens}; GET /api/catalog {servers}.
 * POST /api/session {sessionId}; X-Xiaoman-Session in subsequent requests.
 * All /api/* use X-Xiaoman-Access when configured; 401 asks for a new code.
 * 404 {error:"会话已过期"} creates a session and retries the request once.
 * POST /api/chat {message,model:{baseUrl,model,apiKey} | model:{classToken},mcp:{enabled,keys,custom},memory:{notes},now}.
 *   classToken (only when /api/info classTokens is true): the server calls the instructor's model with its own key,
 *   metered per token; the token is kept only in this browser (xiaoman.model).
 *   memory.notes = [{permalink,markdown,updatedAt}] from localStorage (xiaoman.memory); the server keeps
 *   them only for this turn and never writes them to disk.
 * POST SSE data JSON types: reply{text}, tool{tool,ok,summary},
 * memory{action:"saved",permalink,title,markdown,updatedAt} | memory{action:"deleted",permalink},
 * approval{id,title,server,tool,arguments,reason}, question{id,question},
 * reminder{text,at,ics}, warning{message}, error{message}, done. 409 = busy.
 * POST /api/answer {id,approved} or {id,text} -> {ok}.
 * POST /api/mcp/test {mcp} -> {servers:[{name,title,connected,toolCount,error}],warnings}.
 * Errors: {error}. Storage keys begin xiaoman.; history is capped at 200 rows.
 * parseSseChunk accepts an unconsumed string (including partial lines) and returns
 * complete JSON events plus rest. Call again with rest + the next decoded chunk.
 * linkify returns [{text, href?}]; render with textContent/createElement only.
 */
function parseSseChunk(buffer) {
  const events = [];
  const separator = /\r\n\r\n|\r\n\n|\n\r\n|\n\n|\r\r/g;
  let start = 0;
  let match;
  while ((match = separator.exec(buffer)) !== null) {
    const block = buffer.slice(start, match.index);
    start = separator.lastIndex;
    const data = [];
    let eventName = '';
    for (const line of block.split(/\r\n|\n|\r/)) {
      if (!line || line.startsWith(':')) continue;
      const colon = line.indexOf(':');
      const field = colon < 0 ? line : line.slice(0, colon);
      let value = colon < 0 ? '' : line.slice(colon + 1);
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'data') data.push(value);
      if (field === 'event') eventName = value;
    }
    if (data.length && data.join('\n').trim()) {
      const event = JSON.parse(data.join('\n'));
      if (!event || typeof event !== 'object' || Array.isArray(event)) {
        throw new Error('服务器返回的事件格式不正确');
      }
      if (eventName && !event.type) event.type = eventName;
      events.push(event);
    }
  }
  return { events, rest: buffer.slice(start) };
}

/* Model settings: mode "own" (baseUrl/model/apiKey) or "class" (a class token issued by the instructor). */
function cleanModel(value, defaultUrl) {
  const v = value && typeof value === 'object' ? value : {};
  const text = key => typeof v[key] === 'string' ? v[key] : '';
  return {mode: v.mode === 'class' ? 'class' : 'own', baseUrl: typeof v.baseUrl === 'string' ? v.baseUrl : defaultUrl,
    model: text('model'), apiKey: text('apiKey'), classToken: text('classToken')};
}
/** Whether chatting is possible with these settings; class mode needs the server to offer it. */
function modelReady(model, classEnabled) {
  if (model.mode === 'class') return !!(classEnabled && model.classToken.trim());
  return !!(model.baseUrl.trim() && model.model.trim() && model.apiKey.trim());
}
/** The "model" object of a chat request: only the fields of the chosen mode leave the browser. */
function modelRequest(model) {
  if (model.mode === 'class') return {classToken: model.classToken.trim()};
  return {baseUrl: model.baseUrl, model: model.model, apiKey: model.apiKey};
}

/* Long-term memory kept in this browser. Notes are Basic Memory markdown, the same format as the Android app. */
const MEMORY_LIMIT = 200;
const MEMORY_BYTES = 180 * 1024;
function cleanNotes(value) {
  if (!Array.isArray(value)) return [];
  const seen = new Map();
  for (const item of value) {
    if (!item || typeof item.permalink !== 'string' || !item.permalink || typeof item.markdown !== 'string') continue;
    seen.set(item.permalink, {permalink: item.permalink, title: typeof item.title === 'string' && item.title ? item.title : item.permalink,
      markdown: item.markdown, updatedAt: Number.isFinite(item.updatedAt) ? item.updatedAt : 0});
  }
  return Array.from(seen.values());
}
/** Returns the new note list after a memory event; unknown events leave it unchanged. */
function applyMemoryEvent(notes, event) {
  if (!event || typeof event.permalink !== 'string' || !event.permalink) return notes;
  const rest = notes.filter(note => note.permalink !== event.permalink);
  if (event.action === 'deleted') return rest;
  if (event.action !== 'saved' || typeof event.markdown !== 'string') return notes;
  return [...rest, {permalink: event.permalink, title: typeof event.title === 'string' && event.title ? event.title : event.permalink,
    markdown: event.markdown, updatedAt: Number.isFinite(event.updatedAt) ? event.updatedAt : Date.now()}];
}
/** Newest notes first until the count or size budget is used; {notes, dropped} for the chat request. */
function memoryPayload(notes, limit = MEMORY_LIMIT, bytes = MEMORY_BYTES) {
  const sorted = [...notes].sort((a, b) => b.updatedAt - a.updatedAt);
  const picked = [];
  let size = 0;
  for (const note of sorted) {
    const row = {permalink: note.permalink, markdown: note.markdown, updatedAt: note.updatedAt};
    const cost = new TextEncoder().encode(JSON.stringify(row)).length + 1;
    if (picked.length >= limit || size + cost > bytes) continue;
    picked.push(row); size += cost;
  }
  return {notes: picked, dropped: notes.length - picked.length};
}
/** Observation lines ("- [category] content") of a note, for display. */
function noteLines(markdown) {
  const lines = [];
  for (const line of String(markdown || '').split('\n')) {
    const match = /^[-*+]\s+\[([^\[\]]+)\]\s*(.*)$/.exec(line.trim());
    if (match) lines.push({category: match[1], content: match[2]});
  }
  return lines;
}

/** "2026-11-06T08:00" -> "11月6日 周五 08:00"; other values are shown as they are. */
function reminderTime(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(String(value || ''));
  if (!match) return String(value || '');
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number(match[2]) + '月' + Number(match[3]) + '日 周' + '日一二三四五六'[date.getDay()] + ' ' + match[4] + ':' + match[5];
}

function linkify(value) {
  const text = String(value == null ? '' : value);
  const parts = [];
  const allowed = new Set(['http:', 'https:', 'iosamap:', 'amapuri:', 'baidumap:', 'qqmap:', 'taobao:', 'diditaxi:']);
  const candidate = /[a-z][a-z0-9+.-]*:\/\/[^\s<>"'`，。！？；、（）【】「」]*/gi;
  let end = 0;
  let match;
  while ((match = candidate.exec(text)) !== null) {
    // Never turn an embedded/unknown scheme (e.g. javascript:https://...) into a link.
    if (match.index > 0 && /[a-z0-9_:+.\-/]/i.test(text[match.index - 1])) continue;
    const urlText = match[0].replace(/[.,!?;:)\]}]+$/, '');
    const scheme = urlText.slice(0, urlText.indexOf(':') + 1).toLowerCase();
    if (!allowed.has(scheme)) continue;
    try {
      const url = new URL(urlText);
      if ((scheme === 'http:' || scheme === 'https:') && !url.hostname) continue;
      if (url.username || url.password) continue;
    } catch (_) { continue; }
    if (match.index > end) parts.push({ text: text.slice(end, match.index) });
    parts.push({ text: urlText, href: urlText });
    end = match.index + urlText.length;
  }
  if (end < text.length || !parts.length) parts.push({ text: text.slice(end) });
  return parts;
}

if (typeof document !== 'undefined') {
  (() => {
    const $ = id => document.getElementById(id);
    const DEFAULT_URL = 'https://ark.cn-beijing.volces.com/api/v3';
    const prefix = 'xiaoman.';
    const knownTypes = new Set(['user', 'reply', 'tool', 'memory', 'approval', 'question', 'reminder', 'warning', 'error']);
    function read(key, fallback, session = false) {
      try {
        const value = (session ? sessionStorage : localStorage).getItem(prefix + key);
        return value === null ? fallback : JSON.parse(value);
      } catch (_) { return fallback; }
    }
    function store(key, value, session = false) {
      try { (session ? sessionStorage : localStorage).setItem(prefix + key, JSON.stringify(value)); }
      catch (_) { notify('浏览器无法保存本地数据。当前页面仍可使用，关闭后可能丢失设置。'); }
    }
    function remove(key, session = false) {
      try { (session ? sessionStorage : localStorage).removeItem(prefix + key); } catch (_) { /* storage unavailable */ }
    }
    let model = cleanModel(read('model', {}), DEFAULT_URL);
    let classEnabled = false;
    const storedMcp = read('mcp', {});
    let mcp = {
      enabled: Array.isArray(storedMcp?.enabled) ? storedMcp.enabled.filter(id => typeof id === 'string') : [],
      keys: storedMcp?.keys && typeof storedMcp.keys === 'object' && !Array.isArray(storedMcp.keys) ? storedMcp.keys : {},
      custom: Array.isArray(storedMcp?.custom) ? storedMcp.custom.filter(item => item && typeof item.url === 'string' && typeof item.title === 'string').map(item => ({title: item.title, url: item.url, headers: typeof item.headers?.Authorization === 'string' ? {Authorization: item.headers.Authorization} : {}, confirm: ['writes', 'all', 'none'].includes(item.confirm) ? item.confirm : 'writes'})) : []
    };
    let accessCode = read('access', '');
    if (typeof accessCode !== 'string') accessCode = '';
    let sessionId = read('session', '', true);
    if (typeof sessionId !== 'string') sessionId = '';
    let notes = cleanNotes(read('memory', []));
    let history = read('history', []);
    history = Array.isArray(history) ? history.filter(row => row && knownTypes.has(row.type)).slice(-200) : [];
    for (const row of history) {
      if ((row.type === 'approval' || row.type === 'question') && (!row.status || row.status === 'pending')) row.status = 'expired';
    }
    let catalog = [];
    let testResults = [];
    let busy = false;
    let ready = false;
    let accessPromise = null;
    let sessionPromise = null;
    let bootPromise = null;
    let activeTab = 'chat';
    const activeCards = new Set();

    function node(tag, className, text) {
      const el = document.createElement(tag);
      if (className) el.className = className;
      if (text !== undefined) el.textContent = String(text == null ? '' : text);
      return el;
    }
    function button(text, action, className = '') {
      const el = node('button', className, text);
      el.type = 'button';
      el.addEventListener('click', action);
      return el;
    }
    function linkedText(el, text) {
      for (const part of linkify(text)) {
        if (part.href) {
          const anchor = node('a', '', part.text);
          anchor.href = part.href;
          anchor.rel = 'noopener noreferrer';
          if (/^https?:/i.test(part.href)) anchor.target = '_blank';
          el.append(anchor);
        } else el.append(document.createTextNode(part.text));
      }
    }
    function webLink(value) {
      const parts = linkify(value);
      return parts.length === 1 && parts[0].href && /^https?:\/\//i.test(parts[0].href) ? parts[0].href : null;
    }
    function notify(message) {
      $('notice').replaceChildren(node('span', '', message), button('关闭', () => { $('notice').hidden = true; }, 'notice-close'));
      $('notice').hidden = false;
    }
    function saveHistory() { store('history', history.slice(-200)); }
    function saveNotes() { store('memory', notes); renderMemory(); }
    function renderMemory() {
      const list = $('memory-list');
      list.replaceChildren();
      const payload = memoryPayload(notes);
      const kb = Math.ceil(new TextEncoder().encode(JSON.stringify(notes)).length / 1024);
      $('memory-count').textContent = notes.length ? '共 ' + notes.length + ' 条，约 ' + kb + ' KB' : '';
      $('memory-warning').hidden = payload.dropped === 0;
      $('memory-warning').textContent = '记忆太多，最早的 ' + payload.dropped + ' 条暂时不会发给小满。可以删掉不需要的。';
      $('clear-memory').disabled = !notes.length;
      if (!notes.length) { list.append(node('p', 'muted', '还没有记忆。聊天时说「记住……」，小满就会记在这里。')); return; }
      for (const note of [...notes].sort((a, b) => b.updatedAt - a.updatedAt)) {
        const item = node('article', 'memory-item');
        const top = node('div', 'service-top');
        top.append(node('strong', '', note.title), button('删除', () => {
          if (busy) { notify('请等本次对话结束后再删除记忆。'); return; }
          if (!window.confirm('删除记忆「' + note.title + '」？')) return;
          notes = notes.filter(other => other.permalink !== note.permalink); saveNotes();
        }, 'danger'));
        top.lastChild.setAttribute('aria-label', '删除记忆 ' + note.title);
        item.append(top);
        const lines = noteLines(note.markdown);
        if (lines.length) {
          const ul = node('ul', 'memory-lines');
          for (const line of lines) { const li = node('li'); li.append(node('span', 'memory-tag', line.category), document.createTextNode(' ' + line.content)); ul.append(li); }
          item.append(ul);
        }
        if (note.updatedAt) item.append(node('p', 'muted memory-time', '更新于 ' + new Date(note.updatedAt).toLocaleString('zh-CN', {hour12: false})));
        list.append(item);
      }
    }
    function saveMcp() { store('mcp', mcp); updateCount(); }
    function modelConfigured() { return modelReady(model, classEnabled); }
    function updateChat() {
      $('empty-chat').hidden = history.length > 0;
      $('model-hint').hidden = modelConfigured();
      $('send-button').disabled = busy || !ready || !$('message-input').value.trim();
      $('thinking').hidden = !busy;
      $('message-input').disabled = busy;
      document.querySelectorAll('[data-example]').forEach(el => { el.disabled = busy; });
    }
    function showTab(tab) {
      activeTab = tab;
      for (const name of ['chat', 'services', 'settings']) $('page-' + name).hidden = name !== tab;
      document.querySelectorAll('[data-tab]').forEach(el => {
        const selected = el.dataset.tab === tab;
        el.classList.toggle('active', selected);
        if (selected) el.setAttribute('aria-current', 'page'); else el.removeAttribute('aria-current');
      });
      if (tab === 'chat') scrollChat();
    }
    function scrollChat() { requestAnimationFrame(() => { $('messages').scrollTop = $('messages').scrollHeight; }); }
    function askAccess(reason) {
      if (accessPromise) return accessPromise;
      const previousFocus = document.activeElement;
      $('access-description').textContent = reason || '请输入服务器的访问码。';
      $('access-input').value = '';
      $('access-overlay').hidden = false;
      $('access-input').focus();
      // Keep focus in the access dialog, including hardware keyboards on iPad/iPhone.
      const trap = event => {
        if (event.key === 'Escape') { event.preventDefault(); $('access-cancel').click(); }
        if (event.key !== 'Tab') return;
        const items = [$('access-input'), $('access-cancel'), $('access-form').querySelector('[type="submit"]')];
        const index = items.indexOf(document.activeElement);
        if (event.shiftKey && index <= 0) { event.preventDefault(); items[2].focus(); }
        else if (!event.shiftKey && (index === 2 || index < 0)) { event.preventDefault(); items[0].focus(); }
      };
      accessPromise = new Promise((resolve, reject) => {
        const finish = () => {
          $('access-overlay').hidden = true;
          $('access-form').removeEventListener('submit', submit);
          $('access-cancel').removeEventListener('click', cancel);
          $('access-overlay').removeEventListener('keydown', trap);
          if (previousFocus && previousFocus.isConnected) previousFocus.focus();
        };
        const submit = event => {
          event.preventDefault();
          const code = $('access-input').value.trim();
          if (!code) return;
          accessCode = code;
          store('access', accessCode);
          finish(); resolve();
        };
        const cancel = () => { finish(); reject(new Error('已取消连接。再次操作时，可重新输入访问码。')); };
        $('access-form').addEventListener('submit', submit);
        $('access-cancel').addEventListener('click', cancel);
        $('access-overlay').addEventListener('keydown', trap);
      }).finally(() => { accessPromise = null; });
      return accessPromise;
    }
    async function ensureSession() {
      if (sessionId) return sessionId;
      if (!sessionPromise) sessionPromise = (async () => {
        const response = await api('/api/session', {method: 'POST', skipSession: true});
        const data = await response.json();
        if (!data.sessionId || typeof data.sessionId !== 'string') throw new Error('服务器没有返回会话，请重新连接。');
        sessionId = data.sessionId;
        store('session', sessionId, true);
        return sessionId;
      })().finally(() => { sessionPromise = null; });
      return sessionPromise;
    }
    async function api(path, options = {}, sessionRetried = false) {
      if (!options.skipSession) await ensureSession();
      for (;;) {
        const requestSession = sessionId;
        const headers = {Accept: options.stream ? 'text/event-stream' : 'application/json'};
        if (accessCode) headers['X-Xiaoman-Access'] = accessCode;
        if (requestSession) headers['X-Xiaoman-Session'] = requestSession;
        if (options.body !== undefined) headers['Content-Type'] = 'application/json';
        const response = await fetch(path, {method: options.method || 'GET', headers, body: options.body === undefined ? undefined : JSON.stringify(options.body), credentials: 'same-origin', cache: 'no-store'});
        if (response.status === 401) {
          await response.body?.cancel();
          await askAccess('访问码不正确或已失效，请重新输入。');
          continue;
        }
        if (response.ok) return response;
        let detail = {};
        try { detail = await response.json(); } catch (_) { /* use friendly fallback */ }
        if (response.status === 404 && detail.error === '会话已过期' && path !== '/api/session' && !sessionRetried) {
          if (sessionId === requestSession) { sessionId = ''; remove('session', true); }
          await ensureSession();
          return api(path, options, true);
        }
        throw new Error(response.status === 409 ? '上一条消息还在处理，请稍后再试。' : detail.error || '连接失败（' + response.status + '），请稍后再试。');
      }
    }
    function renderRecord(row) {
      const el = node('article', 'message ' + row.type);
      if (row.type === 'user' || row.type === 'reply') {
        const bubble = node('div', 'bubble');
        linkedText(bubble, row.text);
        el.append(bubble);
      } else if (row.type === 'tool') {
        el.classList.add(row.ok ? 'success' : 'failure');
        const line = node('div', 'event-line');
        line.append(node('span', '', row.ok ? '✓ ' : '× '));
        linkedText(line, row.summary || row.tool || '工具调用');
        el.append(line);
      } else if (row.type === 'approval' || row.type === 'question') {
        const card = node('div', 'action-card');
        card.append(node('span', 'eyebrow', row.type === 'approval' ? '需要你确认' : '小满问'));
        card.append(node('h3', '', row.type === 'approval' ? row.title || '允许这次调用吗？' : row.question));
        if (row.type === 'approval') {
          card.append(node('p', 'muted', '服务：' + (row.server || '未知') + ' · 工具：' + (row.tool || '未知')));
          card.append(node('pre', 'arguments', typeof row.arguments === 'string' ? row.arguments : JSON.stringify(row.arguments == null ? {} : row.arguments, null, 2)));
          if (row.reason) card.append(node('p', '', row.reason));
        }
        const result = node('p', 'card-result');
        const controls = [];
        let submitting = false;
        const pending = () => !row.status || row.status === 'pending';
        const refresh = () => {
          controls.forEach(control => { control.disabled = submitting || !pending(); });
          const labels = {approved: '已允许', denied: '已拒绝', answered: '已回复：' + (row.answer || ''), expired: '这次请求已结束，请重新发送消息。'};
          result.textContent = submitting ? '正在提交…' : labels[row.status] || '';
        };
        const submitAnswer = async payload => {
          if (submitting || !pending()) return;
          submitting = true; refresh();
          try {
            const response = await api('/api/answer', {method: 'POST', body: {id: row.id, ...payload}});
            const data = await response.json();
            if (!data.ok) throw new Error('回复未成功，请重试。');
            row.status = 'approved' in payload ? (payload.approved ? 'approved' : 'denied') : 'answered';
            if ('text' in payload) row.answer = payload.text;
            saveHistory();
          } catch (error) {
            result.textContent = error.message;
            submitting = false;
            controls.forEach(control => { control.disabled = !pending(); });
            return;
          }
          submitting = false; refresh();
        };
        if (row.type === 'approval') {
          const actions = node('div', 'toolbar');
          const allow = button('允许', () => submitAnswer({approved: true}), 'primary');
          const deny = button('拒绝', () => submitAnswer({approved: false}));
          controls.push(allow, deny); actions.append(allow, deny); card.append(actions);
        } else {
          const form = node('form', 'question-form');
          const label = node('label', '', '你的回答');
          const input = node('input');
          input.type = 'text'; input.required = true; input.placeholder = '在这里回答…'; input.maxLength = 10000;
          label.append(input);
          const send = node('button', 'primary', '回复'); send.type = 'submit';
          controls.push(input, send); form.append(label, send);
          form.addEventListener('submit', event => { event.preventDefault(); if (input.value.trim()) submitAnswer({text: input.value.trim()}); });
          card.append(form);
        }
        card.append(result); el.append(card); refresh();
        if (pending()) {
          const expire = () => { if (pending()) { row.status = 'expired'; refresh(); } activeCards.delete(expire); };
          activeCards.add(expire);
        }
      } else if (row.type === 'reminder') {
        const card = node('div', 'action-card');
        card.append(node('span', 'eyebrow', '提醒'), node('h3', '', row.text), node('p', 'muted', reminderTime(row.at)));
        const add = button('添加到日历', () => {
          const blob = new Blob([row.ics], {type: 'text/calendar;charset=utf-8'});
          const url = URL.createObjectURL(blob);
          const anchor = node('a'); anchor.href = url; anchor.download = 'xiaoman-reminder.ics';
          document.body.append(anchor); anchor.click(); anchor.remove();
          // Leave Safari time to hand the calendar file to its native preview.
          setTimeout(() => URL.revokeObjectURL(url), 60000);
        }, 'primary');
        add.disabled = typeof row.ics !== 'string' || !row.ics;
        card.append(add); el.append(card);
      } else {
        const line = node('div', 'event-line');
        if (row.type === 'memory') {
          line.append(node('span', '', row.action === 'deleted' ? '已忘记 · ' : '已记住 · '));
          linkedText(line, row.title || row.permalink || '');
          if (row.action !== 'deleted') {
            const view = button('查看', () => { showTab('settings'); $('memory-panel').scrollIntoView({block: 'start'}); }, 'inline-link');
            line.append(' ', view);
          }
        }
        else linkedText(line, row.message || (row.type === 'error' ? '发生了一个错误，请稍后重试。' : '请留意这条提示。'));
        el.append(line);
      }
      return el;
    }
    function addRecord(row) {
      history.push(row);
      $('messages').append(renderRecord(row));
      while (history.length > 200) { history.shift(); $('messages').firstElementChild?.remove(); }
      saveHistory(); updateChat();
      if (activeTab === 'chat') scrollChat();
    }
    function expireCards() { for (const expire of Array.from(activeCards)) expire(); saveHistory(); }
    function eventRecord(event) {
      const type = event.type || event.event;
      if (type === 'done') return true;
      if (!knownTypes.has(type) || type === 'user') return false;
      const row = {type};
      if (type === 'memory') { notes = applyMemoryEvent(notes, event); saveNotes(); }
      const fields = {reply: ['text'], tool: ['tool', 'ok', 'summary'], memory: ['action', 'permalink', 'title'], approval: ['id', 'title', 'server', 'tool', 'arguments', 'reason'], question: ['id', 'question'], reminder: ['text', 'at', 'ics'], warning: ['message'], error: ['message']};
      for (const key of fields[type]) row[key] = event[key];
      if (type === 'approval' || type === 'question') row.status = 'pending';
      addRecord(row);
      return false;
    }
    async function consumeStream(response) {
      if (!response.body) throw new Error('浏览器不支持流式消息，请更新 Safari。');
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let doneEvent = false;
      try {
        for (;;) {
          const chunk = await reader.read();
          buffer += chunk.done ? decoder.decode() : decoder.decode(chunk.value, {stream: true});
          const parsed = parseSseChunk(buffer);
          buffer = parsed.rest;
          for (const event of parsed.events) {
            if (eventRecord(event)) { doneEvent = true; break; }
          }
          if (doneEvent) { await reader.cancel(); break; }
          if (chunk.done) {
            if (buffer.trim()) throw new Error('连接中断，收到的消息不完整，请重试。');
            throw new Error('连接提前结束，请检查最后一条回复后重试。');
          }
        }
      } finally {
        if (!doneEvent) { try { await reader.cancel(); } catch (_) { /* connection already closed */ } }
        reader.releaseLock();
      }
    }
    function localNow() {
      const date = new Date();
      const pad = n => String(n).padStart(2, '0');
      return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + 'T' + pad(date.getHours()) + ':' + pad(date.getMinutes());
    }
    async function sendMessage() {
      const message = $('message-input').value.trim();
      if (!message || busy) return;
      if (!modelConfigured()) {
        if (model.mode === 'class' && !classEnabled) notify('服务器现在没有开启课堂令牌，请改用自己的 API Key。');
        else notify(model.mode === 'class' ? '先填好课堂令牌，再开始聊天。' : '先填好模型地址、模型名和 API Key，再开始聊天。');
        showTab('settings'); (model.mode === 'class' && classEnabled ? $('class-token') : $('model-name')).focus(); return;
      }
      if (!ready) { notify('正在等待连接服务器，请稍后再试。'); return; }
      // Snapshot current configuration for this entire request (including a retry).
      const memory = memoryPayload(notes);
      const body = JSON.parse(JSON.stringify({message, model: modelRequest(model), mcp, memory: {notes: memory.notes}, now: localNow()}));
      busy = true;
      addRecord({type: 'user', text: message});
      $('message-input').value = ''; updateChat();
      try { await consumeStream(await api('/api/chat', {method: 'POST', stream: true, body})); }
      catch (error) { addRecord({type: 'error', message: error.message || '网络连接中断，请稍后再试。'}); }
      finally { busy = false; expireCards(); updateChat(); }
    }
    function updateCount() { $('enabled-count').textContent = '已启用 ' + (mcp.enabled.length + mcp.custom.length); }
    function testResult(name, title) {
      const result = testResults.find(item => item.name === name || (title && item.title === title));
      if (!result) return null;
      return node('p', 'test-result ' + (result.connected ? 'success' : 'failure'), result.connected ? '已连接 ' + (result.toolCount || 0) + ' 个工具' : '失败：' + (result.error || '未能连接'));
    }
    function secretInput(value, onInput, labelText) {
      const wrapper = node('span', 'secret-field');
      const input = node('input'); input.type = 'password'; input.value = typeof value === 'string' ? value : ''; input.autocomplete = 'off'; input.spellcheck = false;
      input.addEventListener('input', () => onInput(input.value));
      const reveal = button('显示', () => {
        const show = input.type === 'password'; input.type = show ? 'text' : 'password';
        reveal.textContent = show ? '隐藏' : '显示'; reveal.setAttribute('aria-label', (show ? '隐藏' : '显示') + labelText); reveal.setAttribute('aria-pressed', String(show));
      });
      reveal.setAttribute('aria-label', '显示' + labelText); reveal.setAttribute('aria-pressed', 'false');
      wrapper.append(input, reveal); return {wrapper, input};
    }
    function renderCatalog() {
      $('catalog').replaceChildren();
      const groups = new Map();
      for (const server of catalog) {
        const category = server.category || '其他';
        if (!groups.has(category)) {
          const section = node('section', 'catalog-group'); section.append(node('h2', '', category));
          groups.set(category, section); $('catalog').append(section);
        }
        const item = node('div', 'service-item');
        const top = node('div', 'service-top');
        const label = node('label', 'service-toggle');
        const toggle = node('input'); toggle.type = 'checkbox'; toggle.className = 'switch';
        const available = typeof server.available === 'boolean' ? server.available : server.remote === true && (!Array.isArray(server.platforms) || server.platforms.includes('web'));
        toggle.checked = available && mcp.enabled.includes(server.id); toggle.disabled = !available;
        label.append(toggle, node('strong', '', server.title));
        top.append(label, node('span', 'source-tag', server.official ? '官方' : '社区'));
        item.append(top, node('p', 'muted', server.description));
        if (server.note) item.append(node('p', available ? 'muted' : 'muted unavailable', server.note));
        else if (!available) item.append(node('p', 'muted unavailable', '仅支持电脑，网页版暂不可用。'));
        const keys = node('div', 'service-keys fields'); keys.hidden = !toggle.checked;
        for (const key of server.keys || []) {
          const keyLabel = node('label', '', key.label || key.name);
          const secret = secretInput(mcp.keys[key.name], value => {
            mcp.keys[key.name] = value;
            // Shared placeholders are stored once and reflected in every visible input.
            $('catalog').querySelectorAll('input[data-key]').forEach(input => { if (input.dataset.key === key.name && input !== secret.input) input.value = value; });
            testResults = []; clearTestIndicators(); saveMcp();
          }, key.label || key.name);
          secret.input.dataset.key = key.name; keyLabel.append(secret.wrapper); keys.append(keyLabel);
          const help = webLink(key.help || '');
          if (help) { const a = node('a', 'apply-link', '去申请 ↗'); a.href = help; a.target = '_blank'; a.rel = 'noopener noreferrer'; keys.append(a); }
        }
        toggle.addEventListener('change', () => {
          mcp.enabled = mcp.enabled.filter(id => id !== server.id);
          if (toggle.checked) mcp.enabled.push(server.id);
          keys.hidden = !toggle.checked; testResults = []; clearTestIndicators(); saveMcp();
        });
        item.append(keys);
        const result = testResult(server.id, server.title); if (result) item.append(result);
        groups.get(category).append(item);
      }
      if (!catalog.length) $('catalog').append(node('p', 'muted', '暂无可用服务。请尝试重新连接服务器。'));
      updateCount();
    }
    function clearTestIndicators() {
      document.querySelectorAll('.test-result').forEach(el => el.remove());
      $('test-notice').textContent = '';
    }
    function renderCustom() {
      $('custom-list').replaceChildren();
      mcp.custom.forEach((item, index) => {
        const card = node('div', 'custom-item');
        const top = node('div', 'service-top'); top.append(node('strong', '', item.title), button('删除', () => {
          mcp.custom.splice(index, 1); testResults = []; clearTestIndicators(); saveMcp(); renderCustom();
        }, 'danger'));
        card.append(top);
        // Do not expose URL query credentials or Authorization headers in the list.
        let address = 'https 服务';
        try { address = new URL(item.url).origin + new URL(item.url).pathname; } catch (_) { /* invalid saved data */ }
        card.append(node('p', 'muted', address));
        const labels = {writes: '会修改数据时确认', all: '每次都确认', none: '从不确认'};
        card.append(node('p', 'muted', labels[item.confirm]));
        const result = testResult('custom-' + (index + 1), item.title); if (result) card.append(result);
        $('custom-list').append(card);
      });
    }
    function fillModel() {
      $('model-url').value = model.baseUrl; $('model-name').value = model.model; $('model-key').value = model.apiKey;
      $('class-token').value = model.classToken;
      for (const [id, label] of [['model-key', 'API Key'], ['class-token', '课堂令牌']]) {
        $(id).type = 'password';
        const reveal = document.querySelector('[data-reveal="' + id + '"]'); reveal.textContent = '显示'; reveal.setAttribute('aria-pressed', 'false'); reveal.setAttribute('aria-label', '显示' + label);
      }
      const radio = document.querySelector('input[name="model-mode"][value="' + model.mode + '"]'); if (radio) radio.checked = true;
      showMode(); $('model-status').textContent = '';
    }
    /** Shows the fields of the selected mode; hidden fields are disabled so form validation skips them. */
    function selectedMode() { return classEnabled && document.querySelector('input[name="model-mode"]:checked')?.value === 'class' ? 'class' : 'own'; }
    function showMode() {
      $('model-mode').hidden = !classEnabled;
      const mode = selectedMode();
      $('own-fields').hidden = mode !== 'own'; $('class-fields').hidden = mode !== 'class';
      $('own-fields').querySelectorAll('input').forEach(el => { el.disabled = mode !== 'own'; });
      $('class-token').disabled = mode !== 'class';
    }
    function boot() {
      if (bootPromise) return bootPromise;
      bootPromise = (async () => {
        ready = false; updateChat(); $('connection').textContent = '正在连接…';
        try {
          const info = await (await api('/api/info', {skipSession: true})).json();
          $('version').textContent = info.version || '未知版本';
          classEnabled = info.classTokens === true; showMode(); updateChat();
          if (info.accessCodeRequired && !accessCode) await askAccess();
          await ensureSession();
          const data = await (await api('/api/catalog')).json();
          catalog = Array.isArray(data.servers) ? data.servers.filter(item => item && typeof item.id === 'string') : [];
          const usable = server => typeof server.available === 'boolean' ? server.available : server.remote && (!Array.isArray(server.platforms) || server.platforms.includes('web'));
          mcp.enabled = [...new Set(mcp.enabled)].filter(id => catalog.some(server => server.id === id && usable(server)));
          saveMcp(); renderCatalog(); ready = true; $('connection').textContent = '已连接';
        } catch (error) {
          notify(error.message || '无法连接服务器，请检查网络。');
          $('connection').replaceChildren(button('重新连接', boot));
          $('catalog').replaceChildren(node('p', 'muted', '服务目录未加载，请使用顶部的「重新连接」。'));
        } finally { updateChat(); }
      })().finally(() => { bootPromise = null; });
      return bootPromise;
    }

    document.querySelectorAll('[data-tab]').forEach(el => el.addEventListener('click', () => showTab(el.dataset.tab)));
    document.querySelectorAll('[data-example]').forEach(el => el.addEventListener('click', () => { $('message-input').value = el.dataset.example; updateChat(); sendMessage(); }));
    $('go-settings').addEventListener('click', () => showTab('settings'));
    $('chat-form').addEventListener('submit', event => { event.preventDefault(); sendMessage(); });
    $('message-input').addEventListener('input', updateChat);
    // A textarea keeps Enter available for a new line on touch keyboards.
    $('message-input').addEventListener('keydown', event => { if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) { event.preventDefault(); sendMessage(); } });
    document.querySelectorAll('[data-reveal]').forEach(el => {
      el.setAttribute('aria-pressed', 'false');
      el.addEventListener('click', () => {
        const input = $(el.dataset.reveal); const show = input.type === 'password'; input.type = show ? 'text' : 'password'; el.textContent = show ? '隐藏' : '显示'; el.setAttribute('aria-pressed', String(show)); el.setAttribute('aria-label', (show ? '隐藏' : '显示') + ({'model-key': 'API Key', 'class-token': '课堂令牌'}[el.dataset.reveal] || 'Authorization 头'));
      });
    });
    $('model-form').addEventListener('submit', event => {
      event.preventDefault();
      if (selectedMode() === 'class') {
        const token = $('class-token').value.trim();
        if (!/^[A-Za-z0-9_-]{6,100}$/.test(token)) { notify('请粘贴讲师发的完整课堂令牌（xm- 开头）。'); return; }
        model = {...model, mode: 'class', classToken: token};
      } else {
        const baseUrl = $('model-url').value.trim();
        if (!webLink(baseUrl)) { notify('请填写有效的 http 或 https 模型地址。'); return; }
        model = {...model, mode: 'own', baseUrl, model: $('model-name').value.trim(), apiKey: $('model-key').value.trim()};
      }
      if (!modelConfigured()) { notify('请把模型信息填写完整。'); return; }
      store('model', model); $('model-status').textContent = '已保存在这台设备，可以去聊天了。'; updateChat();
    });
    document.querySelectorAll('input[name="model-mode"]').forEach(el => el.addEventListener('change', () => { showMode(); $('model-status').textContent = ''; }));
    $('custom-form').addEventListener('submit', event => {
      event.preventDefault();
      const title = $('custom-title').value.trim(); const url = $('custom-url').value.trim(); const authorization = $('custom-auth').value.trim();
      if (!title) { notify('请填写服务名称。'); return; }
      try { if (!webLink(url) || new URL(url).protocol !== 'https:') throw new Error(); }
      catch (_) { notify('服务地址需要是有效的 https 地址。'); return; }
      if (url.includes('${') || authorization.includes('${') || /[\r\n]/.test(authorization)) { notify('请填写真实地址和请求头，不要使用占位符或换行。'); return; }
      mcp.custom.push({title, url, headers: authorization ? {Authorization: authorization} : {}, confirm: $('custom-confirm').value});
      testResults = []; clearTestIndicators(); saveMcp(); renderCustom(); $('custom-form').reset(); $('custom-auth').type = 'password';
      const reveal = document.querySelector('[data-reveal="custom-auth"]'); reveal.textContent = '显示'; reveal.setAttribute('aria-pressed', 'false'); reveal.setAttribute('aria-label', '显示 Authorization 头');
      notify('服务已添加。可以先测试连接。');
    });
    $('recommend').addEventListener('click', () => {
      if (!ready) { notify('服务目录尚未加载，请先连接服务器。'); return; }
      for (const id of ['amap', 'aihot']) if (!mcp.enabled.includes(id) && catalog.some(server => server.id === id && server.remote && (!Array.isArray(server.platforms) || server.platforms.includes('web')))) mcp.enabled.push(id);
      testResults = []; clearTestIndicators(); saveMcp(); renderCatalog(); notify('已勾选推荐服务。高德地图需要填写 Key，AI 热点无需 Key。');
    });
    $('test-mcp').addEventListener('click', async () => {
      if (!ready) { notify('请先连接服务器。'); return; }
      const testButton = $('test-mcp'); testButton.disabled = true; testButton.textContent = '测试中…';
      const selection = JSON.stringify(mcp);
      try {
        const data = await (await api('/api/mcp/test', {method: 'POST', body: {mcp: JSON.parse(selection)}})).json();
        if (JSON.stringify(mcp) !== selection) { notify('服务设置已改变，请重新测试。'); return; }
        testResults = Array.isArray(data.servers) ? data.servers : [];
        renderCatalog(); renderCustom();
        $('test-notice').textContent = Array.isArray(data.warnings) && data.warnings.length ? data.warnings.join('；') : '测试完成。';
      } catch (error) { notify(error.message || '测试失败，请稍后重试。'); }
      finally { testButton.disabled = false; testButton.textContent = '测试连接'; }
    });
    $('clear-chat').addEventListener('click', () => {
      if (busy) { notify('请等本次对话结束后再清空记录。'); return; }
      if (!window.confirm('清空这台设备上的所有聊天记录？')) return;
      history = []; activeCards.clear(); remove('history'); $('messages').replaceChildren(); updateChat(); notify('聊天记录已清空。');
    });
    $('clear-data').addEventListener('click', () => {
      if (busy || sessionPromise || bootPromise) { notify('请等当前请求结束后再清除数据。'); return; }
      if (!window.confirm('清除聊天、记忆、模型、服务 Key 和访问码？之后需要重新设置。')) return;
      try {
        const keys = [];
        for (let i = 0; i < localStorage.length; i++) { const key = localStorage.key(i); if (key && key.startsWith(prefix)) keys.push(key); }
        keys.forEach(key => localStorage.removeItem(key));
        const sessionKeys = [];
        for (let i = 0; i < sessionStorage.length; i++) { const key = sessionStorage.key(i); if (key && key.startsWith(prefix)) sessionKeys.push(key); }
        sessionKeys.forEach(key => sessionStorage.removeItem(key));
      } catch (_) { notify('浏览器无法清除存储，请在 Safari 网站设置中清除本网站数据。'); return; }
      model = cleanModel({}, DEFAULT_URL); mcp = {enabled: [], keys: {}, custom: []}; history = []; notes = []; accessCode = ''; sessionId = ''; testResults = []; ready = false;
      activeCards.clear(); $('messages').replaceChildren(); fillModel(); renderCatalog(); renderCustom(); renderMemory(); clearTestIndicators(); updateChat();
      $('connection').replaceChildren(button('重新连接', boot)); notify('本地数据已清除，重新连接后即可继续设置。');
    });
    $('clear-memory').addEventListener('click', () => {
      if (busy) { notify('请等本次对话结束后再清空记忆。'); return; }
      if (!window.confirm('清空这台设备上小满的全部记忆？此操作不能撤销。')) return;
      notes = []; remove('memory'); renderMemory(); notify('记忆已清空。');
    });
    // Another tab of the same site changed memory: pick it up.
    window.addEventListener('storage', event => { if (event.key === prefix + 'memory') { notes = cleanNotes(read('memory', [])); renderMemory(); } });
    fillModel(); renderCustom(); renderMemory(); updateCount();
    history.forEach(row => $('messages').append(renderRecord(row)));
    saveHistory(); updateChat(); scrollChat(); boot();
  })();
}

if (typeof module !== 'undefined') module.exports = { parseSseChunk, linkify, reminderTime, cleanNotes, applyMemoryEvent, memoryPayload, noteLines, cleanModel, modelReady, modelRequest };
