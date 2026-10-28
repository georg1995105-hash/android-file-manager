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
  System.out.println("PASS: strict HTTP framing, origin, DNS rebinding and bounded body checks");
 }
}
