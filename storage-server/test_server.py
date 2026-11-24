import base64, hashlib, tempfile, unittest, http.client, json, threading
from server import Vault, Server, CHUNK

def b64(data): return base64.b64encode(data).decode()

class RelayTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.code="a"*64
        self.v=Vault(self.tmp.name,hashlib.sha256(self.code.encode()).hexdigest(),32*1024*1024)
        self.server=Server(("127.0.0.1",0),self.v)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
        self.phone=self.req("POST","/v1/bootstrap",{"name":"phone","code":self.code})["token"]
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join();self.v.close();self.tmp.cleanup()
    def req(self,method,path,body=None,token=None,status=200):
        c=http.client.HTTPConnection("127.0.0.1",self.server.server_port,timeout=5)
        headers={"Authorization":"Bearer "+token} if token else {}
        try:
            c.request(method,path,body if isinstance(body,bytes) else json.dumps(body or {}),headers)
            r=c.getresponse(); data=r.read()
            self.assertEqual(status,r.status,data[:300])
            return json.loads(data) if r.getheader("Content-Type")=="application/json" else data
        finally: c.close()
    def pair(self):
        proof="b"*64
        pid=self.req("POST","/v1/pair",{"name":"TV","proof":proof})["id"]
        path="/v1/pair/"+pid
        self.assertTrue(self.req("POST",path+"/claim",{"proof":proof})["pending"])
        self.req("POST",path+"/approve",{"proof":proof,"wrapped":b64(b"x"*60)},status=401)
        self.req("POST",path+"/approve",{"proof":proof,"wrapped":b64(b"x"*60)},self.phone)
        tv=self.req("POST",path+"/claim",{"proof":proof})
        self.assertEqual(b64(b"x"*60),tv["wrapped"])
        self.req("POST",path+"/claim",{"proof":proof},status=404)
        return tv
    def test_offline_tv_resume_integrity_and_revocation(self):
        path="/v1/files/"+"c"*32
        meta=b64(b"m"*40)
        first=b"a"*(CHUNK+28); second=b"b"*45
        self.req("POST",path,{"size":CHUNK+17,"meta":meta},self.phone)
        self.req("PUT",path+"/1",second,self.phone,status=409)
        self.req("PUT",path+"/0",first,self.phone)
        self.assertEqual(1,self.req("POST",path,{"size":CHUNK+17,"meta":meta},self.phone)["next"])
        self.assertEqual([],self.req("GET","/v1/files",token=self.phone)["files"])
        self.req("POST",path+"/finish",token=self.phone,status=409)
        self.req("PUT",path+"/1",second,self.phone)
        self.req("POST",path+"/finish",token=self.phone)
        # Receiver connects only AFTER sender completes the file.
        tv=self.pair()
        self.assertEqual(meta,self.req("GET","/v1/files",token=tv["token"])["files"][0]["meta"])
        self.assertEqual(first,self.req("GET",path+"/0",token=tv["token"]))
        self.assertEqual(second,self.req("GET",path+"/1",token=tv["token"]))
        self.req("GET",path+"/0",status=401)
        self.req("DELETE","/v1/devices/"+tv["device"],token=self.phone)
        self.req("GET","/v1/files",token=tv["token"],status=401)
        self.req("DELETE",path,token=self.phone)
        self.assertEqual([],self.req("GET","/v1/files",token=self.phone)["files"])
    def test_quota_and_bootstrap(self):
        self.req("POST","/v1/bootstrap",{"name":"intruder","code":self.code},status=409)
        self.req("POST","/v1/files/"+"d"*32,{"size":64*1024*1024,"meta":b64(b"m"*40)},self.phone,status=400)
        self.req("POST","/v1/files/"+"d"*32,{"size":0,"meta":b64(b"m"*40)},self.phone)
        self.req("POST","/v1/files/"+"d"*32+"/finish",token=self.phone)
        self.assertEqual(0,self.req("GET","/v1/files",token=self.phone)["files"][0]["size"])
if __name__=="__main__": unittest.main()
