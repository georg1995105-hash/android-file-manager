package io.personal.filemanager.data.transfer;
import java.io.*;
import java.nio.charset.StandardCharsets;
public class HttpTransportCheck {
 static LocalHttpsServer.Request parse(String text)throws Exception{return LocalHttpsServer.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)));}
 static void reject(String text)throws Exception{try{parse(text);}catch(IOException expected){return;}throw new AssertionError(text);}
 public static void main(String[]args)throws Exception{
  String host="Host: 192.168.1.2:1234\r\n";
  var ok=parse("PUT /upload/123 HTTP/1.1\r\n"+host+"Content-Length: 3\r\n\r\nabcEXTRA");
  if(!new String(ok.body.readAllBytes()).equals("abc"))throw new AssertionError();
  for(String headers:new String[]{"Content-Length: 3\r\nContent-Length: 4\r\n","Transfer-Encoding: chunked\r\n","Content-Length: -1\r\n","Content-Length: 9999999999\r\n","Origin: https://evil.example\r\n","Expect: 100-continue\r\n"})reject("POST /pair HTTP/1.1\r\n"+host+headers+"\r\n");
  reject("GET / HTTP/1.1\r\nHost: attacker.example:1234\r\n\r\n");
  reject("GET /../../secret HTTP/1.1\r\n"+host+"\r\n");
  reject("GET / HTTP/1.1\n"+host+"\r\n");
  reject("GET / HTTP/1.1\r\n"+host+"X: "+"a".repeat(5000)+"\r\n\r\n");
  var r=LocalHttpsServer.parseRange("bytes=8-",10);
  if(r.start!=8||r.end!=9||r.length()!=2)throw new AssertionError("range");
  r=LocalHttpsServer.parseRange("bytes=-3",10);
  if(r.start!=7||r.length()!=3)throw new AssertionError("suffix");
  r=LocalHttpsServer.parseRange("bytes=3-6",10);
  if(r.start!=3||r.end!=6)throw new AssertionError("bounded range");
  if(LocalHttpsServer.parseRange(null,0).length()!=0)throw new AssertionError("empty file");
  for(String bad:new String[]{"bytes=10-","bytes=-0","bytes=9-2","bytes=1-,3-","bytes=9999999999999999999-"}) {
   try { LocalHttpsServer.parseRange(bad,10); throw new AssertionError(bad); } catch(IOException expected) {}
  }
  var response=new ByteArrayOutputStream();LocalHttpsServer.downloadHeaders(response,LocalHttpsServer.parseRange("bytes=8-",10),10,true);
  String range=response.toString("US-ASCII");
  if(!range.contains("206 Response")||!range.contains("Content-Length: 2")||!range.contains("Content-Range: bytes 8-9/10"))throw new AssertionError(range);
  System.out.println("PASS: strict HTTP framing, origin, DNS rebinding, bounded body and Range checks");
 }
}
