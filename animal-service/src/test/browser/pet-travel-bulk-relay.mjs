// WSL browser -> Windows loopback test Java. GET-only, no credentials or payload mocking.
import http from 'node:http';
import {execFile} from 'node:child_process';
if(process.env.PAWBRIDGE_TRAVEL_CONTRACT_TEST!=='true') throw new Error('Explicit test opt-in required');
http.createServer((req,res)=>{
  const url=new URL(req.url,'http://localhost');
  if(req.method!=='GET'||url.search||!/^\/(?:api\/v1\/places\/(?:regions|[0-9]{1,20})|__fixture\/requests)$/.test(url.pathname)) {
    res.writeHead(404);return res.end();
  }
  execFile('/mnt/c/Windows/System32/curl.exe',['--silent','--show-error','--max-time','8','--write-out','\n%{http_code}',
    'http://127.0.0.1:18081'+url.pathname],{timeout:10000,maxBuffer:1048576},(error,stdout)=>{
      if(error){res.writeHead(502);return res.end();}
      const cut=stdout.lastIndexOf('\n');
      res.writeHead(Number(stdout.slice(cut+1)),{'Content-Type':'application/json','Cache-Control':'no-store'});
      res.end(stdout.slice(0,cut));
    });
}).listen(18082,'127.0.0.1',()=>console.log('BULK_READONLY_RELAY_READY'));
