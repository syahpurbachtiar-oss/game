(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.MimiEngine = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const KEY = 'mimi-pet-v1';
  const FOODS = {
    kibble: { name: 'Biskuit', icon: 'bowl', price: 0, hunger: 22, energy: 2, fun: 2 },
    fish: { name: 'Ikan', icon: 'fish', price: 12, hunger: 36, energy: 4, fun: 7 },
    milk: { name: 'Susu kucing', icon: 'milk', price: 8, hunger: 21, energy: 8, fun: 3 },
    donut: { name: 'Donat kucing', icon: 'donut', price: 10, hunger: 18, energy: 3, fun: 12 }
  };
  const HATS = {
    none: { name: 'Natural', price: 0, icon: 'paw' },
    bow: { name: 'Pita manis', price: 55, icon: 'bow' },
    leaf: { name: 'Daun mungil', price: 75, icon: 'leaf' },
    crown: { name: 'Raja kecil', price: 120, icon: 'crown' },
    cap: { name: 'Anak santai', price: 90, icon: 'cap' }
  };
  const finite = (v, fallback) => Number.isFinite(Number(v)) ? Number(v) : fallback;
  const clamp = (v, min = 0, max = 100) => Math.max(min, Math.min(max, v));
  function dayKey(now) { const d = new Date(now); return `${d.getFullYear()}-${d.getMonth()+1}-${d.getDate()}`; }
  function create(now = Date.now()) {
    return { version: 1, name: 'Mimi', coins: 100, xp: 0, hunger: 76, fun: 82, hygiene: 68, energy: 87,
      sleeping: false, hat: 'none', owned: ['none'], sound: true, updated: now,
      day: dayKey(now), daily: { feed: 0, wash: 0, game: 0, claimed: false }, gift: '',
      best: { catch: 0, memory: 0 }, lastReward: 0 };
  }
  function normalize(raw, now = Date.now()) {
    const base = create(now);
    if (!raw || raw.version !== 1) return base;
    for (const k of ['hunger', 'fun', 'hygiene', 'energy']) base[k] = clamp(finite(raw[k], base[k]));
    base.coins = Math.floor(clamp(finite(raw.coins, 100), 0, 999999));
    base.xp = Math.floor(clamp(finite(raw.xp, 0), 0, 999999));
    base.name = typeof raw.name === 'string' ? raw.name.trim().slice(0, 16) || 'Mimi' : 'Mimi';
    base.sleeping = raw.sleeping === true;
    base.sound = raw.sound !== false;
    base.updated = Math.min(now, Math.max(0, finite(raw.updated, now)));
    base.owned = ['none', ...Object.keys(HATS).filter(k => k !== 'none' && Array.isArray(raw.owned) && raw.owned.includes(k))];
    base.hat = base.owned.includes(raw.hat) ? raw.hat : 'none';
    base.gift = typeof raw.gift === 'string' ? raw.gift : '';
    base.lastReward = clamp(finite(raw.lastReward, 0), 0, now);
    for (const k of ['catch', 'memory']) base.best[k] = Math.floor(clamp(finite(raw.best?.[k], 0), 0, 99999));
    if (raw.day === base.day) {
      for (const k of ['feed', 'wash', 'game']) base.daily[k] = Math.floor(clamp(finite(raw.daily?.[k], 0), 0, 100));
      base.daily.claimed = raw.daily?.claimed === true;
    }
    return base;
  }
  function refreshDay(s, now) {
    if (s.day !== dayKey(now)) { s.day = dayKey(now); s.daily = { feed: 0, wash: 0, game: 0, claimed: false }; }
  }
  function advance(s, now = Date.now()) {
    const seconds = clamp((now - s.updated) / 1000, 0, 8 * 3600);
    s.updated = Math.max(s.updated, now);
    refreshDay(s, now);
    s.hunger = clamp(s.hunger - seconds / 110, 8);
    s.fun = clamp(s.fun - seconds / 150, 8);
    s.hygiene = clamp(s.hygiene - seconds / 190, 8);
    s.energy = clamp(s.energy + seconds * (s.sleeping ? 1.8 : -1 / 180), 8);
    if (s.energy >= 100) s.sleeping = false;
    return s;
  }
  function level(s) { return 1 + Math.floor(s.xp / 100); }
  function xp(s, amount) { const before = level(s); s.xp += amount; const gained = level(s) - before; if (gained > 0) s.coins += gained * 25; return gained; }
  function rewardCare(s, now) {
    if (now - s.lastReward >= 10000) { s.lastReward = now; return xp(s, 8); }
    return 0;
  }
  function care(s, action, choice, now = Date.now()) {
    advance(s, now);
    if (action === 'sleep') { s.sleeping = !s.sleeping; return { ok: true, sleeping: s.sleeping }; }
    if (s.sleeping) return { ok: false, reason: 'sleeping' };
    if (action === 'feed') {
      const food = FOODS[choice];
      if (!food) return { ok: false, reason: 'invalid' };
      if (s.hunger > 96) return { ok: false, reason: 'full' };
      if (s.coins < food.price) return { ok: false, reason: 'coins' };
      s.coins -= food.price;
      for (const k of ['hunger', 'fun', 'energy']) s[k] = clamp(s[k] + food[k]);
      s.hygiene = clamp(s.hygiene - 3); s.daily.feed++;
    } else if (action === 'wash') { s.hygiene = clamp(s.hygiene + 38); s.fun = clamp(s.fun + 4); s.daily.wash++; }
    else if (action === 'brush') { s.hygiene = clamp(s.hygiene + 18); s.fun = clamp(s.fun + 8); s.daily.wash++; }
    else if (action === 'pet') { s.fun = clamp(s.fun + 5); }
    else if (action === 'play') { s.fun = clamp(s.fun + 15); s.energy = clamp(s.energy - 3, 8); }
    else return { ok: false, reason: 'invalid' };
    return { ok: true, levels: rewardCare(s, now) };
  }
  function shop(s, hat) {
    if (!HATS[hat]) return { ok: false, reason: 'invalid' };
    const owned = s.owned.includes(hat);
    if (!owned && s.coins < HATS[hat].price) return { ok: false, reason: 'coins' };
    if (!owned) { s.coins -= HATS[hat].price; s.owned.push(hat); }
    s.hat = hat; return { ok: true, bought: !owned };
  }
  function gameReward(s, kind, score, now = Date.now()) {
    if (!['catch', 'memory'].includes(kind)) return { ok: false };
    advance(s, now);
    const points = Math.floor(clamp(finite(score, 0), 0, kind === 'catch' ? 100 : 300));
    const coins = kind === 'catch' ? Math.max(5, points * 2) : Math.max(8, Math.floor(points / 5));
    const best = points > s.best[kind]; s.best[kind] = Math.max(points, s.best[kind]);
    s.coins += coins; s.fun = clamp(s.fun + 25); s.energy = clamp(s.energy - 7, 8);
    s.hunger = clamp(s.hunger - 5, 8); s.daily.game++;
    return { ok: true, coins, best, levels: xp(s, 25) };
  }
  function gift(s, now = Date.now()) { refreshDay(s, now); if (s.gift === s.day) return false; s.gift = s.day; s.coins += 40; return true; }
  function missionReady(s) { return s.daily.feed >= 2 && s.daily.wash >= 1 && s.daily.game >= 1; }
  function claimMission(s, now = Date.now()) { refreshDay(s, now); if (!missionReady(s) || s.daily.claimed) return false; s.daily.claimed = true; s.coins += 60; xp(s, 20); return true; }
  return { KEY, FOODS, HATS, create, normalize, advance, level, care, shop, gameReward, gift, missionReady, claimMission, dayKey };
});
