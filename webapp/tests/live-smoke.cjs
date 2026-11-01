// Optional network smoke check; the publisher's catalogue may change.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require(process.env.PLAYWRIGHT_PATH||'playwright');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/snap/bin/chromium',headless:true,args:['--no-sandbox']});
 try {
  const page=await browser.newPage({viewport:{width:1440,height:1000}});
  // Verify the prepared bundle on the real HTTPS origin without deploying it.
  // Only /app assets are supplied locally; feeds, Apple and audio stay live.
  if(process.env.POD4ME_PREPARED_ASSETS==='1') {
    const path=require('node:path'),root=path.resolve('webapp/public/app');
    await page.route('**/app/**',async route=>{
      const url=new URL(route.request().url()),relative=decodeURIComponent(url.pathname.slice('/app/'.length))||'index.html';
      const file=path.resolve(root,relative);
      if(!file.startsWith(root+path.sep))return route.abort();
      const types={'.html':'text/html','.mjs':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png','.webmanifest':'application/manifest+json'};
      return route.fulfill({body:fs.readFileSync(file),contentType:types[path.extname(file)]||'application/octet-stream'});
    });
    console.log('Prepared /app assets intercepted locally; remote deployment is unchanged.');
  }

  const appleResponses=[];page.on('response',response=>{const url=new URL(response.url());if(url.hostname==='itunes.apple.com')appleResponses.push({path:url.pathname,status:response.status()});});
  await page.goto((process.env.POD4ME_TEST_URL||'http://127.0.0.1:18876')+'/app/');
  await page.locator('[data-action="add-feed"]').first().click();
  await page.locator('#feed-url').fill('https://radiofrance-podcast.net/podcast09/podcast_90382906-44e0-11e5-9fe0-005056a87c89.xml');
  await page.locator('[data-action="confirm-feed"]').click();
  await page.locator('.episode').first().waitFor({timeout:45000});
  const count=await page.locator('.episode').count();assert.ok(count>0);
  console.log('Live Radio France feed parsed and saved:',count,'episodes');
  await page.locator('[data-action="play"]').first().click();
  await page.waitForFunction(()=>{const a=document.querySelector('#audio');return !a.paused&&a.currentTime>1},{},{timeout:45000});
  console.log('Live publisher audio playing:',await page.locator('#audio').evaluate(a=>({time:a.currentTime,duration:a.duration})));
  await page.locator('[data-action="toggle-play"]').click();
  await page.locator('[data-action="nav"][data-id="discover"]').click();
  await page.locator('#discovery-query').fill('science');
  await page.locator('#discovery-query').press('Enter');
  await page.locator('.discovery-card').first().waitFor({timeout:45000});
  console.log('Live Apple browser search:',await page.locator('.discovery-card').count(),'results');
  await page.locator('[data-action="suggest"]').click();
  await page.waitForFunction(()=>document.querySelector('.section-heading h2')?.textContent==='A few things you might like'&&document.querySelector('#operation').hidden,{},{timeout:90000});
  console.log('Live Apple browser chart + suggestions:',await page.locator('.discovery-card').count(),'results',appleResponses);
  assert.ok(appleResponses.some(r=>r.path.includes('/rss/')&&r.status===200),'Chart did not succeed');
  assert.ok(appleResponses.some(r=>r.path==='/lookup'&&r.status===200),'Chart lookup did not succeed');

  fs.mkdirSync('dist/web-qa',{recursive:true});
  await page.screenshot({path:'dist/web-qa/desktop-radiofrance.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:'dist/web-qa/mobile-radiofrance.png',fullPage:true});
 } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
