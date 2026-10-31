// Optional network smoke check; the publisher's catalogue may change.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require(process.env.PLAYWRIGHT_PATH||'playwright');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/snap/bin/chromium',headless:true,args:['--no-sandbox']});
 try {
  const page=await browser.newPage({viewport:{width:1440,height:1000}});
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
  fs.mkdirSync('dist/web-qa',{recursive:true});
  await page.screenshot({path:'dist/web-qa/desktop-radiofrance.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:'dist/web-qa/mobile-radiofrance.png',fullPage:true});
 } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
