const test=require('node:test');
const assert=require('node:assert/strict');
const E=require('../engine.js');
const now=new Date(2026,9,10,14,0).getTime();
test('fresh state and corrupted save recovery',()=>{
 assert.equal(E.normalize(null,now).name,'Mimi');
 const s=E.normalize({version:1,name:'<b>A</b>',coins:-99,hunger:900,fun:'no',hat:'crown',owned:['fake'],updated:Infinity},now);
 assert.equal(s.coins,0);assert.equal(s.hunger,100);assert.equal(s.fun,82);assert.equal(s.hat,'none');assert.deepEqual(s.owned,['none']);assert.equal(s.updated,now);
});
test('feeding costs exactly once and full pet is not charged',()=>{
 const s=E.create(now);s.hunger=35;assert.ok(E.care(s,'feed','fish',now).ok);assert.equal(s.coins,88);assert.equal(s.hunger,71);assert.equal(s.daily.feed,1);
 s.hunger=99;const before=s.coins;assert.equal(E.care(s,'feed','fish',now).reason,'full');assert.equal(s.coins,before);
 s.hunger=50;s.coins=0;assert.equal(E.care(s,'feed','fish',now).reason,'coins');assert.ok(E.care(s,'feed','kibble',now).ok);assert.equal(s.coins,0);
});
test('sleep restores energy across reload and blocks care until awake',()=>{
 const s=E.create(now);s.energy=20;E.care(s,'sleep',null,now);assert.equal(E.care(s,'wash',null,now).reason,'sleeping');E.advance(s,now+10000);assert.equal(s.energy,38);
 const restored=E.normalize(JSON.parse(JSON.stringify(s)),now+70000);E.advance(restored,now+70000);assert.equal(restored.energy,100);assert.equal(restored.sleeping,false);
});
test('offline decay is bounded and never moves clock backwards',()=>{
 const s=E.create(now);E.advance(s,now+365*86400000);for(const key of ['hunger','fun','hygiene','energy'])assert.ok(s[key]>=8&&s[key]<=100);const values={...s};E.advance(s,now);assert.equal(s.updated,values.updated);assert.equal(s.energy,values.energy);
});
test('store requires balance and owned accessories equip for free',()=>{
 const s=E.create(now);assert.equal(E.shop(s,'crown').reason,'coins');assert.ok(E.shop(s,'bow').bought);assert.equal(s.coins,45);assert.ok(!E.shop(s,'bow').bought);assert.equal(s.coins,45);E.shop(s,'none');assert.equal(s.hat,'none');assert.equal(E.shop(s,'prototype').ok,false);
});
test('daily gift and mission cannot be claimed twice, reset next day',()=>{
 const s=E.create(now);assert.ok(E.gift(s,now));assert.equal(s.coins,140);assert.equal(E.gift(s,now),false);assert.equal(E.claimMission(s,now),false);
 s.daily.feed=2;s.daily.wash=1;s.daily.game=1;assert.ok(E.claimMission(s,now));assert.equal(E.claimMission(s,now),false);assert.equal(s.coins,200);
 E.advance(s,now+86400000);assert.equal(s.daily.feed,0);assert.equal(s.daily.claimed,false);assert.ok(E.gift(s,now+86400000));
});
test('XP rate limit, level-up coins, mini-game records',()=>{
 const s=E.create(now);s.xp=95;assert.equal(E.care(s,'pet',null,now).levels,1);assert.equal(E.level(s),2);assert.equal(s.coins,125);E.care(s,'pet',null,now+500);assert.equal(s.xp,103);
 E.care(s,'pet',null,now+11000);assert.equal(s.xp,111);
 let r=E.gameReward(s,'catch',12,now+11000);assert.equal(r.coins,24);assert.equal(s.best.catch,12);assert.equal(s.daily.game,1);
 r=E.gameReward(s,'catch',7,now+11000);assert.equal(r.best,false);assert.equal(s.best.catch,12);
 assert.equal(E.gameReward(s,'invalid',12,now).ok,false);
});
test('serialization retains progress and treats saved future clock safely',()=>{
 const s=E.create(now);E.shop(s,'leaf');E.gift(s,now);s.sleeping=true;const out=E.normalize(JSON.parse(JSON.stringify(s)),now);assert.deepEqual(out,s);
 const future=E.normalize({...s,updated:now+99999999},now);assert.equal(future.updated,now);
});
