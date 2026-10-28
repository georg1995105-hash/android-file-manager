const {chromium}=require('playwright');const fs=require('fs');const assert=require('assert');
(async()=>{
 const root=process.argv[2],port=fs.readFileSync(root+'/port','utf8');
 const browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox','--ignore-certificate-errors']});
 try{
 const context=await browser.newContext({ignoreHTTPSErrors:true,acceptDownloads:true});
 const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('https://127.0.0.1:'+port);await page.locator('#code').fill('12345678');await page.locator('#connect').click();await page.locator('#session').waitFor({state:'visible'});
 const expected=Buffer.alloc(2*1024*1024+23);for(let i=0;i<expected.length;i++)expected[i]=i%251;
 await page.locator('#pick').setInputFiles({name:'fixture.bin',mimeType:'application/octet-stream',buffer:expected});
 let interrupted=false;await page.route('**/upload/fixture-upload',async route=>{if(route.request().method()==='PUT'&&!interrupted){interrupted=true;await route.abort();}else await route.continue();});
 await page.locator('#send').click();await page.waitForFunction(()=>document.querySelector('#status').textContent.includes('При обрыве можно'),{},{timeout:30000});
 await page.locator('#resume').click();await page.waitForFunction(()=>document.querySelector('#status').textContent.includes('Получен и проверен:'),{},{timeout:30000});
 assert.deepStrictEqual(fs.readFileSync(fs.readFileSync(root+'/result-path','utf8')),expected);
 await page.locator('#refresh').click();await page.locator('#files button').click();
 const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#files a').click()]);const path=await download.path();assert.deepStrictEqual(fs.readFileSync(path),expected);
 assert.deepStrictEqual(errors,[]);await page.screenshot({path:root+'/browser.png',fullPage:true});console.log('PASS: Chromium HTTPS upload, interruption/resume, SHA-256 publication, streamed download (2 MiB + 23 bytes). Fixture pairing, not Android pairing.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
