'use strict';
/*
 * /claim: self-service class tokens, one per TRAE forum account.
 * GET /api/info -> claim {state: open|full|expired|off, topicUrl, expiresAt, remaining, max, totalTokens, dailyTokens, model}
 * POST /api/claim/start {username} -> {challengeId, phrase, username, topicUrl, expiresInSeconds}
 * POST /api/claim/verify {challengeId} -> {token, prefix, username, expiresAt, totalTokens, dailyTokens, model}
 * Errors: {ok:false, code, error}. The token is shown once; this tab keeps it in sessionStorage until closed.
 */

/** Merges a claimed token into the web app's saved model settings (localStorage xiaoman.model), keeping own-key fields. */
function withClassToken(saved, token) {
  const v = saved && typeof saved === 'object' && !Array.isArray(saved) ? saved : {};
  return {...v, mode: 'class', classToken: token};
}
/** 1000000 -> "100 万". */
function amountText(n) {
  if (typeof n !== 'number' || !isFinite(n)) return '-';
  return n >= 10000 && n % 10000 === 0 ? (n / 10000) + ' 万' : String(n);
}

if (typeof document !== 'undefined') {
  (() => {
    const $ = id => document.getElementById(id);
    const steps = ['loading', 'closed', 'name', 'phrase', 'done'];
    const KEY = 'xiaoman.claim';
    let info = {};
    let challenge = null;
    let timer = null;

    function show(step) { steps.forEach(s => { $('step-' + s).hidden = s !== step; }); }
    function notice(text, kind = 'error') {
      const el = $('claim-notice');
      el.textContent = text || ''; el.className = 'claim-notice ' + kind; el.hidden = !text;
      if (text) el.scrollIntoView({block: 'nearest'});
    }
    function remember(value) { try { value ? sessionStorage.setItem(KEY, JSON.stringify(value)) : sessionStorage.removeItem(KEY); } catch (_) { /* private mode */ } }
    function recall() { try { return JSON.parse(sessionStorage.getItem(KEY) || 'null'); } catch (_) { return null; } }
    async function post(path, body) {
      let response;
      try {
        response = await fetch(path, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
      } catch (_) { return {ok: false, error: '网络连接失败，请检查网络后重试'}; }
      let data = {};
      try { data = await response.json(); } catch (_) { /* not JSON */ }
      if (!response.ok || data.ok === false) return {ok: false, code: data.code, error: data.error || ('请求失败（' + response.status + '）')};
      return data;
    }
    async function copy(text, button) {
      let ok = false;
      try { await navigator.clipboard.writeText(text); ok = true; } catch (_) {
        // Older iOS Safari: select a temporary field and use execCommand.
        const area = document.createElement('textarea'); area.value = text; area.setAttribute('readonly', ''); area.style.position = 'fixed'; area.style.opacity = '0';
        document.body.append(area); area.select(); try { ok = document.execCommand('copy'); } catch (_) { ok = false; } area.remove();
      }
      const label = button.textContent; button.textContent = ok ? '已复制' : '请长按手动复制';
      setTimeout(() => { button.textContent = label; }, 1800);
    }
    function closed(state) {
      const texts = {
        full: ['已经领完了', '这期课堂令牌（' + (info.max || '') + ' 份）已经全部领完。需要的话请联系讲师。'],
        expired: ['领取已结束', '课堂令牌已在 ' + (info.expiresAt || '') + ' 过期，领取也随之关闭。'],
        off: ['自助领取没有开启', '讲师还没有开启自助领取。请等讲师通知，或向讲师要课堂令牌。'],
      };
      const [title, text] = texts[state] || texts.off;
      $('closed-title').textContent = title; $('closed-text').textContent = text; show('closed');
    }
    function showName() {
      if (timer) clearInterval(timer);
      challenge = null; remember(null);
      $('fact-total').textContent = amountText(info.totalTokens);
      $('fact-expires').textContent = info.expiresAt || '-';
      $('fact-remaining').textContent = (info.remaining ?? '-') + ' / ' + (info.max ?? '-');
      show('name'); $('username').focus();
    }
    function showPhrase(c) {
      challenge = c; remember({challenge: c});
      $('phrase-user').textContent = c.username; $('phrase').textContent = c.phrase;
      $('topic-link').href = c.topicUrl;
      if (timer) clearInterval(timer);
      const tick = () => {
        const left = Math.max(0, Math.round((c.deadline - Date.now()) / 1000));
        $('phrase-timer').textContent = left > 0 ? '短语 ' + Math.floor(left / 60) + ' 分 ' + String(left % 60).padStart(2, '0') + ' 秒内有效' : '短语已过期，请换一个用户名或重新获取';
        if (left <= 0) clearInterval(timer);
      };
      tick(); timer = setInterval(tick, 1000);
      show('phrase');
    }
    function showDone(r) {
      if (timer) clearInterval(timer);
      remember({done: r});
      $('done-user').textContent = r.username; $('token').textContent = r.token;
      $('done-total').textContent = amountText(r.totalTokens); $('done-daily').textContent = amountText(r.dailyTokens);
      $('done-expires').textContent = r.expiresAt;
      $('android-url').textContent = location.origin + '/v1';
      $('android-model').textContent = r.model || info.model || 'xiaoman-class';
      show('done');
    }

    $('name-form').addEventListener('submit', async event => {
      event.preventDefault();
      const username = $('username').value.trim().replace(/^@/, '');
      if (!username) { notice('请填写论坛用户名'); $('username').focus(); return; }
      $('name-submit').disabled = true; notice('');
      const r = await post('/api/claim/start', {username});
      $('name-submit').disabled = false;
      if (!r.ok) { notice(r.error, r.code === 'already_claimed' ? 'info' : 'error'); if (r.code === 'claim_full' || r.code === 'claim_expired') boot(); return; }
      showPhrase({id: r.challengeId, phrase: r.phrase, username: r.username, topicUrl: r.topicUrl, deadline: Date.now() + r.expiresInSeconds * 1000});
    });
    $('copy-phrase').addEventListener('click', event => copy($('phrase').textContent, event.currentTarget));
    $('copy-token').addEventListener('click', event => copy($('token').textContent, event.currentTarget));
    document.querySelectorAll('[data-copy]').forEach(el => el.addEventListener('click', () => copy($(el.dataset.copy).textContent, el)));
    $('restart').addEventListener('click', () => { notice(''); showName(); });
    $('verify').addEventListener('click', async () => {
      if (!challenge) return;
      $('verify').disabled = true; $('verify').textContent = '正在论坛里查找你的回复…'; notice('');
      const r = await post('/api/claim/verify', {challengeId: challenge.id});
      $('verify').disabled = false; $('verify').textContent = '我已回复，领取令牌';
      if (!r.ok) {
        notice(r.error, r.code === 'already_claimed' ? 'info' : 'error');
        if (r.code === 'challenge_expired') showName();
        if (r.code === 'claim_full' || r.code === 'claim_expired') boot();
        return;
      }
      notice('');
      showDone(r);
    });
    $('use-here').addEventListener('click', () => {
      const token = $('token').textContent;
      try {
        let saved = null;
        try { saved = JSON.parse(localStorage.getItem('xiaoman.model') || 'null'); } catch (_) { saved = null; }
        localStorage.setItem('xiaoman.model', JSON.stringify(withClassToken(saved, token)));
      } catch (_) { notice('浏览器不允许保存设置（可能是无痕模式）。请复制令牌，到网页版设置里手动填写。'); return; }
      location.href = '/#claimed';
    });

    async function boot() {
      show('loading');
      try {
        const data = await (await fetch('/api/info', {cache: 'no-store'})).json();
        info = data && typeof data.claim === 'object' && data.claim ? data.claim : {state: 'off'};
      } catch (_) { info = {state: 'off'}; notice('连不上服务器，请稍后刷新重试'); }
      const saved = recall();
      if (saved?.done?.token) return showDone(saved.done);
      if (info.state !== 'open') return closed(info.state);
      if (saved?.challenge?.id && saved.challenge.deadline > Date.now()) return showPhrase(saved.challenge);
      showName();
    }
    boot();
  })();
}

if (typeof module !== 'undefined') module.exports = { withClassToken, amountText };
