"""Single-owner encrypted-object relay. Run behind HTTPS; payload keys never leave clients."""
import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

CHUNK = 4 * 1024 * 1024
HEX = re.compile(r"^[0-9a-f]{64}$")
ID = re.compile(r"^[0-9a-f]{32}$")
MAX_JSON = 65536
PAIR_LIFETIME = 300

class ApiError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message

def require(condition, status=400, message="invalid request"):
    if not condition:
        raise ApiError(status, message)

def digest(s):
    return hashlib.sha256(s.encode("ascii")).hexdigest()

def valid_blob(s, low, high):
    try:
        raw = base64.b64decode(s, validate=True)
        return low <= len(raw) <= high
    except (ValueError, TypeError):
        return False

class Vault:
    def __init__(self, root, bootstrap_hash, quota):
        require(HEX.fullmatch(bootstrap_hash), 500, "invalid bootstrap hash")
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.objects = self.root / "objects"
        self.objects.mkdir(exist_ok=True, mode=0o700)
        self.bootstrap = bootstrap_hash
        self.quota = quota
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.root / "vault.sqlite3", check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
          PRAGMA journal_mode=WAL;
          PRAGMA synchronous=FULL;
          CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, name TEXT, token TEXT UNIQUE, owner INTEGER);
          CREATE TABLE IF NOT EXISTS pairs(id TEXT PRIMARY KEY, name TEXT, proof TEXT, expires INTEGER, wrapped TEXT);
          CREATE TABLE IF NOT EXISTS files(id TEXT PRIMARY KEY, owner TEXT, size INTEGER, meta TEXT,
                                           next INTEGER DEFAULT 0, done INTEGER DEFAULT 0, created INTEGER);
        """)
        for p in self.objects.iterdir():
            if p.is_dir() and ID.fullmatch(p.name) and not self.db.execute("SELECT 1 FROM files WHERE id=?", (p.name,)).fetchone():
                shutil.rmtree(p)
    def close(self):
        self.db.close()
    def auth(self, header):
        require(header.startswith("Bearer "), 401, "authentication required")
        token = header[7:]
        require(HEX.fullmatch(token), 401, "invalid token")
        row = self.db.execute("SELECT * FROM devices WHERE token=?", (digest(token),)).fetchone()
        require(row is not None, 401, "device not authorized")
        return row
    def add_device(self, name, owner=0):
        require(isinstance(name, str) and 1 <= len(name) <= 80)
        token, device = secrets.token_hex(32), secrets.token_hex(16)
        self.db.execute("INSERT INTO devices VALUES(?,?,?,?)", (device,name,digest(token),owner))
        return {"token":token,"device":device}
    def dispatch(self, method, path, header, body):
        parts = path.strip("/").split("/")
        require(parts[0] == "v1", 404)
        with self.lock:
            now = int(time.time())
            self.db.execute("DELETE FROM pairs WHERE expires<=?", (now,))
            if parts == ["v1","bootstrap"] and method == "POST":
                require(not self.db.execute("SELECT 1 FROM devices").fetchone(), 409, "already initialized")
                key = body.get("code","")
                require(isinstance(key,str) and HEX.fullmatch(key) and hmac.compare_digest(digest(key),self.bootstrap), 403, "invalid setup code")
                return self.add_device(body.get("name"),1)
            if parts == ["v1","pair"] and method == "POST":
                proof,name=body.get("proof"),body.get("name")
                require(isinstance(proof,str) and HEX.fullmatch(proof) and isinstance(name,str) and 1<=len(name)<=80)
                require(self.db.execute("SELECT count(*) FROM pairs").fetchone()[0]<50,429,"pairing busy")
                pair=secrets.token_hex(16)
                self.db.execute("INSERT INTO pairs VALUES(?,?,?,?,NULL)",(pair,name,proof,now+PAIR_LIFETIME))
                return {"id":pair}
            if len(parts)==4 and parts[1]=="pair" and ID.fullmatch(parts[2]) and method=="POST":
                pair=self.db.execute("SELECT * FROM pairs WHERE id=?",(parts[2],)).fetchone()
                require(pair is not None,404,"pairing expired")
                proof=body.get("proof","")
                require(isinstance(proof,str) and hmac.compare_digest(proof,pair["proof"]),403,"invalid pairing")
                if parts[3]=="approve":
                    self.auth(header)
                    require(pair["wrapped"] is None,409,"already approved")
                    wrapped=body.get("wrapped")
                    require(valid_blob(wrapped,60,60))
                    self.db.execute("UPDATE pairs SET wrapped=? WHERE id=?",(wrapped,pair["id"]))
                    return {"ok":True}
                if parts[3]=="claim":
                    if pair["wrapped"] is None:
                        return {"pending":True}
                    self.db.execute("BEGIN IMMEDIATE")
                    try:
                        device=self.add_device(pair["name"])
                        self.db.execute("DELETE FROM pairs WHERE id=?",(pair["id"],))
                        self.db.execute("COMMIT")
                    except Exception:
                        self.db.execute("ROLLBACK"); raise
                    return {**device,"wrapped":pair["wrapped"]}
                raise ApiError(404,"unknown pairing operation")
            device=self.auth(header)
            if parts==["v1","devices"] and method=="GET":
                return {"devices":[dict(x) for x in self.db.execute("SELECT id,name,owner FROM devices ORDER BY owner DESC,name")]}
            if len(parts)==3 and parts[1]=="devices" and ID.fullmatch(parts[2]) and method=="DELETE":
                require(device["owner"],403,"owner only")
                require(parts[2]!=device["id"],400,"cannot revoke owner")
                self.db.execute("DELETE FROM devices WHERE id=? AND owner=0",(parts[2],))
                return {"ok":True}
            if parts==["v1","files"] and method=="GET":
                return {"files":[dict(x) for x in self.db.execute("SELECT id,size,meta,created FROM files WHERE done=1 ORDER BY created DESC LIMIT 1000")]}
            require(len(parts)>=3 and parts[1]=="files" and ID.fullmatch(parts[2]),404)
            ident=parts[2]
            row=self.db.execute("SELECT * FROM files WHERE id=?",(ident,)).fetchone()
            if len(parts)==3 and method=="POST":
                size,meta=body.get("size"),body.get("meta")
                require(type(size) is int and 0<=size<=self.quota and valid_blob(meta,28,8192))
                if row:
                    require(row["owner"]==device["id"] and row["size"]==size and row["meta"]==meta,409,"file identity conflict")
                    return {"next":row["next"],"done":bool(row["done"])}
                used=self.db.execute("SELECT COALESCE(sum(size+28*((size+?-1)/?)+8192),0) FROM files",(CHUNK,CHUNK)).fetchone()[0]
                require(used+size+28*((size+CHUNK-1)//CHUNK)+8192<=self.quota,413,"storage quota exceeded")
                require(self.db.execute("SELECT count(*) FROM files").fetchone()[0]<1000,413,"file count limit")
                require(shutil.disk_usage(self.root).free>size+64*1024*1024,507,"disk space insufficient")
                (self.objects/ident).mkdir(mode=0o700,exist_ok=True)
                self.db.execute("INSERT INTO files(id,owner,size,meta,created) VALUES(?,?,?,?,?)",(ident,device["id"],size,meta,now))
                return {"next":0,"done":False}
            require(row is not None,404,"file not found")
            if not row["done"]:
                require(row["owner"]==device["id"],404,"file not found")
            if len(parts)==3 and method=="GET":
                return {"next":row["next"],"done":bool(row["done"]),"size":row["size"],"meta":row["meta"]}
            if len(parts)==3 and method=="DELETE":
                require(row["owner"]==device["id"] or device["owner"],403,"not file owner")
                self.db.execute("DELETE FROM files WHERE id=?",(ident,))
                shutil.rmtree(self.objects/ident,ignore_errors=True)
                return {"ok":True}
            if len(parts)==4 and parts[3]=="finish" and method=="POST":
                require(row["owner"]==device["id"],403)
                require(row["next"]==(row["size"]+CHUNK-1)//CHUNK,409,"incomplete upload")
                self.db.execute("UPDATE files SET done=1 WHERE id=?",(ident,))
                return {"ok":True}
            if len(parts)==4 and parts[3].isdigit() and len(parts[3])<=10:
                index=int(parts[3]); count=(row["size"]+CHUNK-1)//CHUNK
                require(index<count,404,"chunk not found")
                target=self.objects/ident/str(index)
                if method=="PUT":
                    require(row["owner"]==device["id"] and not row["done"],403)
                    require(index==row["next"],409,"unexpected chunk index")
                    require(isinstance(body,bytes) and len(body)==min(CHUNK,row["size"]-index*CHUNK)+28)
                    temp=target.with_suffix(".part")
                    with temp.open("wb") as f:
                        f.write(body); f.flush(); os.fsync(f.fileno())
                    os.replace(temp,target)
                    fd=os.open(target.parent,os.O_RDONLY)
                    try: os.fsync(fd)
                    finally: os.close(fd)
                    self.db.execute("UPDATE files SET next=next+1 WHERE id=?",(ident,))
                    return {"next":index+1}
                if method=="GET":
                    require(row["done"],409,"incomplete upload")
                    return target.read_bytes()
            raise ApiError(404,"unknown operation")

class Handler(BaseHTTPRequestHandler):
    protocol_version="HTTP/1.0"
    def setup(self):
        super().setup()
        self.connection.settimeout(35)
    def log_message(self, fmt, *args):
        # Do not log pairing codes, credentials or request bodies.
        pass
    def handle_api(self):
        try:
            path=urlsplit(self.path).path
            require(len(path)<=256 and not self.headers.get("Transfer-Encoding"))
            length=self.headers.get("Content-Length","0")
            require(length.isdigit() and len(length)<=10)
            length=int(length)
            limit=CHUNK+28 if self.command=="PUT" else MAX_JSON
            require(0<=length<=limit,413,"request too large")
            raw=self.rfile.read(length)
            require(len(raw)==length)
            body=raw if self.command=="PUT" else json.loads(raw or b"{}")
            require(isinstance(body,(bytes,dict)))
            result=self.server.vault.dispatch(self.command,path,self.headers.get("Authorization",""),body)
            self.reply(200,result)
        except ApiError as e:
            self.reply(e.status,{"error":e.message})
        except (ValueError,TypeError,UnicodeError):
            self.reply(400,{"error":"invalid request"})
        except (BrokenPipeError,ConnectionResetError,TimeoutError):
            pass
        except Exception:
            self.reply(500,{"error":"storage service error"})
    def reply(self,status,result):
        is_binary=isinstance(result,bytes)
        data=result if is_binary else json.dumps(result,separators=(",",":")).encode()
        self.send_response(status)
        self.send_header("Content-Type","application/octet-stream" if is_binary else "application/json")
        self.send_header("Content-Length",str(len(data)))
        self.send_header("Cache-Control","no-store")
        self.send_header("X-Content-Type-Options","nosniff")
        self.end_headers()
        self.wfile.write(data)
    do_GET=do_POST=do_PUT=do_DELETE=handle_api

class Server(ThreadingHTTPServer):
    daemon_threads=True
    def __init__(self, addr, vault):
        self.vault=vault
        self.slots=threading.BoundedSemaphore(16)
        super().__init__(addr,Handler)
    def process_request(self,request,address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try: super().process_request(request,address)
        except Exception:
            self.slots.release(); raise
    def process_request_thread(self,request,address):
        try: super().process_request_thread(request,address)
        finally: self.slots.release()

if __name__=="__main__":
    os.umask(0o077)
    parser=argparse.ArgumentParser()
    parser.add_argument("--data",default="/var/lib/pfm-storage")
    parser.add_argument("--port",type=int,default=8787)
    parser.add_argument("--quota-gib",type=int,default=10)
    args=parser.parse_args()
    vault=Vault(args.data,os.environ["PFM_BOOTSTRAP_SHA256"],args.quota_gib*1024**3)
    server=Server(("127.0.0.1",args.port),vault)
    try: server.serve_forever()
    finally: server.server_close(); vault.close()
