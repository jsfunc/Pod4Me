const assert = require('node:assert/strict');
const {parse, intent} = require('../website/link.js');
const raw = 'https://example.com/audio?id=a%2Fb&x=1#chapter';
const feed = 'https://example.com/rss?lang=fr&x=2';
const title = 'A & B + Café? <script>alert(1)</script>';
const hash = '#episode?url=' + encodeURIComponent(raw) + '&title=' + encodeURIComponent(title) + '&feed=' + encodeURIComponent(feed);
const item = parse(hash);
assert.deepEqual(item, {kind:'episode',url:raw,title,feed});
const launch = intent(item);
assert.ok(launch.startsWith('intent://episode?url='));
assert.ok(launch.endsWith('#Intent;scheme=pod4me;package=com.pod4me;end'));
assert.ok(!launch.includes('<script>'));
const query = new URLSearchParams(launch.split('?')[1].split('#Intent')[0]);
assert.equal(query.get('url'), raw);
assert.equal(query.get('feed'), feed);
assert.equal(query.get('title'), title);
assert.equal(parse('#feed?url=https%3A%2F%2Fexample.com%2Frss&title=Radio+France').title, 'Radio France');
assert.equal(parse(''), null);
assert.equal(parse('#'), null);
for (const invalid of [
  '#episode?url=javascript%3Aalert(1)', '#episode?url=file%3A%2F%2F%2Fetc%2Fpasswd',
  '#feed?url=https%3A%2F%2Fuser%3Asecret%40example.com', '#episode?url=', '#episode?title=Missing',
  '#video?url=https%3A%2F%2Fexample.com', '#episode?url=%ZZ',
  '#feed?url=https%3A%2F%2Fa.com&url=https%3A%2F%2Fb.com',
  '#episode?url=https%3A%2F%2Fexample.com&feed=javascript%3Aalert(1)', '#episode?' + 'x'.repeat(64000),
]) assert.throws(() => parse(invalid));
console.log('PASS: website payload and launch-link checks');
