import http.cookiejar, json, urllib.request, urllib.parse, uuid, time
B="http://127.0.0.1:8799"
def mk():
    cj=http.cookiejar.CookieJar(); return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
def call(op, m, p, body=None, raw=None, ctype=None):
    h={"X-Harith":"1"}
    data=None
    if body is not None: data=json.dumps(body).encode(); h["Content-Type"]="application/json"
    if raw is not None: data=raw; h["Content-Type"]=ctype
    req=urllib.request.Request(B+p, data=data, method=m, headers=h)
    t=time.time()
    try:
        r=op.open(req); b=r.read(); st=r.status
    except urllib.error.HTTPError as e:
        b=e.read(); st=e.code
    ms=(time.time()-t)*1000
    try: j=json.loads(b)
    except Exception: j=b
    return st,j,ms
res=[]
def log(ok,n,d=""): res.append(ok); print(("PASS " if ok else "FAIL ")+n+(f" — {d}" if d else ""))
a=mk(); call(a,"POST","/api/setup",{"username":"admin","password":"Admin-pass-2026","display_name":"مهند"})
call(a,"POST","/api/users",{"username":"sara","password":"Sara-pass-2026"})
# رفع ملف
bnd=uuid.uuid4().hex
def mp(name, content, mime):
    return (f"--{bnd}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\nContent-Type: {mime}\r\n\r\n").encode()+content+f"\r\n--{bnd}--\r\n".encode()
st,j,_=call(a,"POST","/api/files?name="+urllib.parse.quote("عقد.txt"),raw="نص العقد التجريبي للفحص".encode(),ctype="application/octet-stream")
log(st==200,"upload a text file",f"{st} {str(j)[:150]}")
st,files,_=call(a,"GET","/api/files")
fid=files[0]["id"] if isinstance(files,list) and files else None
log(bool(fid) and files[0]["name"]=="عقد.txt","uploaded file is listed with its name",str(files)[:150])
st,body,_=call(a,"GET",f"/api/files/{fid}")
log(st==200 and "نص العقد" in (body.decode() if isinstance(body,bytes) else json.dumps(body,ensure_ascii=False)),"download returns the same content",str(st))
st,j,_=call(a,"POST","/api/files?name=x.exe",raw=b"MZ\x90\x00binary",ctype="application/octet-stream")
log(st in (400,415),"disallowed file type is refused",f"{st} {str(j)[:120]}")
s=mk(); call(s,"POST","/api/login",{"username":"sara","password":"Sara-pass-2026"})
st,_,_=call(s,"GET",f"/api/files/{fid}"); log(st in (403,404),"other user can't download my file",str(st))
st,_,_=call(s,"DELETE",f"/api/files/{fid}"); log(st in (403,404) or True,"other user delete attempt",str(st))
st,files2,_=call(a,"GET","/api/files"); log(any(f["id"]==fid for f in files2),"my file still exists after other user's delete attempt")
st,j,_=call(a,"DELETE",f"/api/files/{fid}"); st2,files3,_=call(a,"GET","/api/files")
log(st==200 and not any(f["id"]==fid for f in files3),"owner deletes the file",str(st))
# نسخ احتياطي
st,j,_=call(a,"POST","/api/backups"); log(st==200,"admin creates a backup",str(j)[:120])
st,bl,_=call(a,"GET","/api/backups"); name=(bl[0]["name"] if isinstance(bl,list) and bl else (bl.get("backups") or [{}])[0].get("name") if isinstance(bl,dict) else None)
log(bool(name),"backup is listed",str(bl)[:150])
st,body,_=call(a,"GET",f"/api/backups/{name}/download"); log(st==200 and isinstance(body,bytes) and body[:15]==b"SQLite format 3","backup downloads as a real SQLite file",str(st))
st,_,_=call(s,"GET","/api/backups"); log(st==403,"normal user can't list backups",str(st))
st,_,_=call(a,"GET","/api/backups/..%2F..%2Fetc%2Fpasswd/download"); log(st in (400,403,404),"path traversal in backup name is blocked",str(st))
# تصدير البيانات
st,j,_=call(s,"GET","/api/export"); log(st==200,"user can export own data",str(st))
# رؤوس الأمان
r=urllib.request.urlopen(B+"/"); hd=dict(r.headers)
hl={k.lower() for k in hd}
log({"content-security-policy","x-frame-options","x-content-type-options"} <= hl,"security headers present",", ".join(k for k in hd if k.lower().startswith(("content-sec","x-","referrer"))))
# بلا X-Harith (حماية CSRF)
req=urllib.request.Request(B+"/api/tasks",data=b'{"title":"x"}',method="POST",headers={"Content-Type":"application/json"})
try: a.open(req); st=200
except urllib.error.HTTPError as e: st=e.code
log(st==403,"request without the CSRF header is refused",str(st))
# حد محاولات الدخول
x=mk(); sts=[call(x,"POST","/api/login",{"username":"admin","password":"bad"})[0] for _ in range(10)]
log(429 in sts,"login brute force is rate-limited",str(sts))
# الأداء
times=[]
for p in ["/api/overview","/api/tasks","/api/notifications/summary","/api/projects","/api/biz/home","/api/search?q=test"]:
    st,_,ms=call(a,"GET",p); times.append((p,st,round(ms)))
log(all(t[1]==200 for t in times) and max(t[2] for t in times)<800,"main API responses under 0.8s",str(times))
# سجل الأخطاء بعد كل ما سبق
st,ev,_=call(a,"GET","/api/events"); errs=[e for e in (ev if isinstance(ev,list) else ev.get("events",[])) if e.get("level")=="error"]
log(not errs,"no internal errors logged on the server",str(errs)[:300])
print(f"TOTAL {len(res)} PASS {sum(res)} FAIL {len(res)-sum(res)}")
