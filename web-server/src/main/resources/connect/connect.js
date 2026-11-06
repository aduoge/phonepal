/* Connect page for the local launcher (`web-server --local`): QR code of this computer's LAN address. */
(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  let addresses = [];
  let selected = 0;

  /** Draws the QR code with a 4-module quiet zone; dark modules on white so any scanner can read it. */
  function draw(text) {
    const canvas = $('qr');
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    if (!text) { canvas.setAttribute('aria-label', '没有可用的局域网地址'); return; }
    const qr = qrcode(0, 'M');
    qr.addData(text);
    qr.make();
    const count = qr.getModuleCount();
    const cell = Math.floor(canvas.width / (count + 8));
    const offset = Math.floor((canvas.width - cell * count) / 2);
    ctx.fillStyle = '#000';
    for (let r = 0; r < count; r += 1) {
      for (let c = 0; c < count; c += 1) {
        if (qr.isDark(r, c)) ctx.fillRect(offset + c * cell, offset + r * cell, cell, cell);
      }
    }
    canvas.setAttribute('aria-label', '二维码：' + text);
  }

  function show() {
    const item = addresses[selected];
    draw(item ? item.url : '');
    $('url').textContent = item ? item.url : '没找到局域网地址：请先连上 Wi-Fi 或手机热点，再点“刷新地址”。';
    $('label').textContent = item ? item.label + '：' + item.interface : '';
    const list = $('address-list');
    list.replaceChildren();
    addresses.forEach((a, i) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = a.url + '（' + a.label + '）';
      button.setAttribute('aria-pressed', String(i === selected));
      button.addEventListener('click', () => { selected = i; show(); });
      list.append(button);
    });
    $('others').hidden = addresses.length < 2;
  }

  async function load() {
    $('refresh').disabled = true;
    try {
      const response = await fetch('/connect/info', {cache: 'no-store'});
      if (!response.ok) throw new Error(await response.text());
      const info = await response.json();
      const current = addresses[selected] && addresses[selected].address;
      addresses = Array.isArray(info.addresses) ? info.addresses : [];
      selected = Math.max(0, addresses.findIndex((a) => a.address === current));
      $('code').textContent = info.accessCode || '（不需要访问码）';
      $('stdio').textContent = info.stdioMcp
        ? '12306 火车票：已开启（电脑上装了 Node.js）。'
        : '12306 火车票：电脑上没找到 Node.js，网页里会显示为不可用，其他功能照常。';
      show();
    } catch (error) {
      $('url').textContent = '读取地址失败：' + (error && error.message ? error.message : error);
    } finally {
      $('refresh').disabled = false;
    }
  }

  $('refresh').addEventListener('click', load);
  load();
})();
