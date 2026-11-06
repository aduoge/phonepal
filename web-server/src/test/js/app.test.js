'use strict';
// Run with: node --test web-server/src/test/js  (or ./gradlew :web-server:jsTest)
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const app = require(path.join(__dirname, '../../main/resources/web/app.js'));

const note = (permalink, updatedAt, markdown = '# ' + permalink + '\n\n## Observations\n- [偏好] 喜欢燕麦拿铁\n') =>
  ({permalink, title: permalink, markdown, updatedAt});

test('saved events add or replace a note, deleted events remove it', () => {
  let notes = [note('家', 1)];
  notes = app.applyMemoryEvent(notes, {type: 'memory', action: 'saved', permalink: '偏好/咖啡', title: '咖啡', markdown: '# 咖啡', updatedAt: 5});
  assert.deepEqual(notes.map(n => n.permalink), ['家', '偏好/咖啡']);
  notes = app.applyMemoryEvent(notes, {type: 'memory', action: 'saved', permalink: '家', title: '家', markdown: '# 新家', updatedAt: 6});
  assert.equal(notes.length, 2);
  assert.equal(notes.find(n => n.permalink === '家').markdown, '# 新家');
  notes = app.applyMemoryEvent(notes, {type: 'memory', action: 'deleted', permalink: '家'});
  assert.deepEqual(notes.map(n => n.permalink), ['偏好/咖啡']);
});

test('old or malformed memory events leave notes unchanged', () => {
  const notes = [note('家', 1)];
  assert.equal(app.applyMemoryEvent(notes, {type: 'memory', permalink: '家'}), notes);
  assert.equal(app.applyMemoryEvent(notes, {type: 'memory', action: 'saved', permalink: '家'}), notes);
  assert.equal(app.applyMemoryEvent(notes, null), notes);
});

test('payload sends newest notes first within count and size budgets', () => {
  const notes = [note('a', 1), note('b', 3), note('c', 2)];
  assert.deepEqual(app.memoryPayload(notes).notes.map(n => n.permalink), ['b', 'c', 'a']);
  assert.deepEqual(Object.keys(app.memoryPayload(notes).notes[0]).sort(), ['markdown', 'permalink', 'updatedAt']);
  const limited = app.memoryPayload(notes, 2);
  assert.deepEqual(limited.notes.map(n => n.permalink), ['b', 'c']);
  assert.equal(limited.dropped, 1);
  const big = [note('big', 9, 'x'.repeat(500)), note('small', 1)];
  const sized = app.memoryPayload(big, 200, 300);
  assert.deepEqual(sized.notes.map(n => n.permalink), ['small']);
  assert.equal(sized.dropped, 1);
});

test('stored notes are cleaned and de-duplicated', () => {
  const cleaned = app.cleanNotes([note('a', 1), {permalink: 'a', markdown: '# 2', updatedAt: 2}, {permalink: 3}, null, {permalink: 'b', markdown: 'x'}]);
  assert.deepEqual(cleaned.map(n => [n.permalink, n.title, n.updatedAt]), [['a', 'a', 2], ['b', 'b', 0]]);
  assert.deepEqual(app.cleanNotes('not a list'), []);
});

test('observation lines are extracted for display', () => {
  const lines = app.noteLines('---\ntitle: 咖啡\n---\n# 咖啡\n\n## Observations\n- [偏好] 喜欢燕麦拿铁 #饮品\n* [时间] 早上\n普通文字\n');
  assert.deepEqual(lines, [{category: '偏好', content: '喜欢燕麦拿铁 #饮品'}, {category: '时间', content: '早上'}]);
});

test('existing helpers still parse SSE and links', () => {
  const parsed = app.parseSseChunk('data: {"type":"reply","text":"你好"}\n\ndata: {"type"');
  assert.equal(parsed.events[0].text, '你好');
  assert.equal(parsed.rest, 'data: {"type"');
  assert.deepEqual(app.linkify('看 https://aihot.news/x。').filter(p => p.href).map(p => p.href), ['https://aihot.news/x']);
  assert.equal(app.linkify('javascript:https://a.com').filter(p => p.href).length, 0);
});

test('reminder times are shown in Chinese with the weekday', () => {
  assert.equal(app.reminderTime('2026-11-06T08:00'), '11月6日 周五 08:00');
  assert.equal(app.reminderTime('明天'), '明天');
  assert.equal(app.reminderTime(undefined), '');
});

test('model settings keep own-key and class-token modes apart', () => {
  const url = 'https://ark.cn-beijing.volces.com/api/v3';
  const old = app.cleanModel({baseUrl: url, model: 'ep-1', apiKey: 'k-123'}, url);
  assert.equal(old.mode, 'own');
  assert.ok(app.modelReady(old, false));
  assert.deepEqual(app.modelRequest(old), {baseUrl: url, model: 'ep-1', apiKey: 'k-123'});
  const cls = app.cleanModel({mode: 'class', classToken: ' xm-abc123 ', apiKey: 'k-123', baseUrl: url, model: 'ep-1'}, url);
  assert.ok(app.modelReady(cls, true));
  assert.equal(app.modelReady(cls, false), false, 'class mode needs the server to offer it');
  assert.deepEqual(app.modelRequest(cls), {classToken: 'xm-abc123'}, 'own key never travels in class mode');
  assert.equal(app.modelReady(app.cleanModel({mode: 'class'}, url), true), false);
  assert.equal(app.cleanModel(null, url).baseUrl, url);
  assert.equal(app.cleanModel({mode: 'weird'}, url).mode, 'own');
});
