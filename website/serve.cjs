// Local-only static preview. No dependencies; APK responses support resumable downloads.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const root = __dirname;
const port = Number(process.env.PORT || 4173);
http.createServer((req, res) => {
  let relative;
  try { relative = decodeURIComponent(new URL(req.url, 'http://localhost').pathname); }
  catch { res.writeHead(400).end(); return; }
  if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405).end(); return; }
  const file = path.resolve(root, '.' + (relative === '/' ? '/index.html' : relative));
  if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    res.writeHead(404, {'Content-Type':'text/plain'}).end('Not found'); return;
  }
  const size = fs.statSync(file).size;
  const type = file.endsWith('.apk') ? 'application/vnd.android.package-archive' : file.endsWith('.html') ? 'text/html; charset=utf-8' : 'application/octet-stream';
  const headers = {'Content-Type':type,'Accept-Ranges':'bytes','Cache-Control':'no-cache','X-Content-Type-Options':'nosniff'};
  if (file.endsWith('.apk')) headers['Content-Disposition'] = 'attachment; filename="' + path.basename(file) + '"';
  let start=0,end=size-1,status=200;
  if (req.headers.range) {
    const match=/^bytes=(\d+)-(\d*)$/.exec(req.headers.range);
    if (!match) { res.writeHead(416,{'Content-Range':'bytes */'+size}).end(); return; }
    start=Number(match[1]);end=match[2]?Math.min(Number(match[2]),size-1):size-1;
    if(start>end||start>=size){res.writeHead(416,{'Content-Range':'bytes */'+size}).end();return;}
    status=206;headers['Content-Range']=`bytes ${start}-${end}/${size}`;
  }
  headers['Content-Length']=end-start+1;res.writeHead(status,headers);
  if(req.method==='HEAD')res.end();else{const stream=fs.createReadStream(file,{start,end});stream.pipe(res);res.on('close',()=>stream.destroy());}
}).listen(port,'127.0.0.1',()=>console.log(`CASCADE MESH preview: http://127.0.0.1:${port}`));
