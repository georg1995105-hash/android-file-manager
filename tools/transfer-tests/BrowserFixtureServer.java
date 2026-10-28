package io.personal.filemanager.data.transfer;
import javax.net.ssl.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.regex.*;

/** Disposable browser test fixture, not an application endpoint or production pairing implementation. */
public class BrowserFixtureServer {
 static IncomingTransfer incoming; static Path completed;
 static String field(String json,String name){Matcher m=Pattern.compile("\""+name+"\"\\s*:\\s*\"([^\"]*)\"").matcher(json);if(!m.find())throw new IllegalArgumentException();return m.group(1);}
 static void json(OutputStream out,String s)throws Exception{LocalHttpsServer.reply(out,200,"application/json",s.getBytes());}
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]), assets=Path.of(args[1]);KeyStore keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(root.resolve("tls.p12"))){keys.load(in,"fixture-only".toCharArray());}
  KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"fixture-only".toCharArray());SSLContext tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),null,null);
  LocalHttpsServer server=new LocalHttpsServer(tls.getServerSocketFactory(),(r,out)->{
   if(r.path.equals("/")||r.path.equals("/appjs")||r.path.equals("/style")){String name=r.path.equals("/")?"index.html":r.path.equals("/appjs")?"app.js":"style.css";LocalHttpsServer.reply(out,200,name.endsWith("js")?"text/javascript":name.endsWith("css")?"text/css":"text/html",Files.readAllBytes(assets.resolve(name)));}
   else if(r.path.equals("/pair")){json(out,"{\"id\":\"fixture\"}");}
   else if(r.path.equals("/pair/fixture")){json(out,"{\"token\":\"fixture-session\"}");}
   else if(r.path.equals("/files")){json(out,completed==null?"{\"files\":[]}":"{\"files\":[{\"id\":\"result\",\"name\":\"fixture.bin\",\"size\":"+Files.size(completed)+",\"sha256\":\"fixture\"}]}");}
   else if(r.path.equals("/batch")){json(out,"{\"id\":\"fixture-batch\"}");}
   else if(r.path.equals("/offer")){String data=new String(r.body.readAllBytes());Matcher m=Pattern.compile("\"size\"\\s*:\\s*(\\d+)").matcher(data);if(!m.find())throw new IllegalArgumentException();incoming=new IncomingTransfer(root.resolve("stage"),root.resolve("done"),field(data,"name"),Long.parseLong(m.group(1)),field(data,"sha256"),10_000_000);json(out,"{\"id\":\"fixture-upload\"}");}
   else if(r.path.equals("/upload/fixture-upload")){switch(r.method){case "GET":json(out,"{\"accepted\":true,\"offset\":"+incoming.offset()+",\"done\":false}");break;case "PUT":incoming.append(Long.parseLong(r.headers.get("x-offset")),(int)r.length,r.headers.get("x-sha256"),r.body);json(out,"{\"offset\":"+incoming.offset()+"}");break;case "POST":completed=incoming.finish();Files.writeString(root.resolve("result-path"),completed.toString());json(out,"{}");break;default:incoming.close();json(out,"{}");}}
   else if(r.path.equals("/ticket/result")){json(out,"{\"url\":\"/download/result\"}");}
   else if(r.path.equals("/download/result")){LocalHttpsServer.headers(out,200,"application/octet-stream",Files.size(completed));Files.copy(completed,out);}
  });
  Files.writeString(root.resolve("port"),Integer.toString(server.port()));server.run();
 }
}
