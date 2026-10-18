// Local ContractServer + MySQL; provider fixtures, not fabricated API responses.
// Enable PAWBRIDGE_TRAVEL_BULK_CONTRACT_TEST. Relay 18082 -> Windows backend 18081.
async page => {
  const checks=[];
  const check=(ok,label)=>{if(!ok)throw new Error(label);checks.push(label);};
  const backend='http://127.0.0.1:18082';
  const before=await (await page.request.get(backend+'/__fixture/requests')).json();
  await page.route('**/api/**',async route=>{
    const suffix=route.request().url().split('/api/')[1];
    if(!/^places(?:[/?]|$)/.test(suffix)||route.request().method()!=='GET') return route.abort();
    const response=await route.fetch({url:backend+'/api/v1/'+suffix,maxRedirects:0,maxRetries:0});
    await route.fulfill({response});
  });
  const detail=await (await page.request.get(backend+'/api/v1/places/124')).json();
  check(detail.petInformationStatus==='READY'&&detail.petInformationAvailable,'bulk conditions ready');
  check(detail.overview===null,'common overview has not been collected');
  check(detail.conditions.requirements==='이동장 지참','actual DB supplies bulk condition');
  check((await page.request.get(backend+'/api/v1/places/999')).status()===404,'unknown ID stays private');
  for(const width of [1440,375]) {
    await page.setViewportSize({width,height:900});
    await page.goto('http://127.0.0.1:5198/travel/124?areaCode=36110');
    await page.getByText('이동장 지참',{exact:true}).waitFor();
    check(await page.getByRole('heading',{name:'장소 소개',exact:true}).count()===0,'missing overview hidden '+width);
    check(await page.getByText('동반 조건 확인 중입니다.',{exact:false}).count()===0,'bulk result not marked pending '+width);
    check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'no horizontal overflow '+width);
    check(await page.getByRole('link',{name:'세종특별자치시 목록으로'}).count()===1,'region return link preserved '+width);
  }
  const after=await (await page.request.get(backend+'/__fixture/requests')).json();
  check(after.requests===before.requests,'browsing makes no provider requests');
  return {count:checks.length,checks,actualCollector:true,actualMySql:true,providerFixture:true,actualGateway:false};
}
