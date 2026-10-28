package io.personal.filemanager.data.transfer;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Small, deliberately restricted HTTP/1.1 transport: one request per TLS connection,
 * fixed length bodies only, bounded headers/workers, no proxying, no filesystem routing. */
public final class LocalHttpsServer implements AutoCloseable {
    public interface Handler { void handle(Request request, OutputStream response) throws Exception; }
    public static final class Request {
        public final String method, path;
        public final Map<String,String> headers;
        public final long length;
        public final InputStream body;
        Request(String method, String path, Map<String,String> headers, long length, InputStream body) {
            this.method=method; this.path=path; this.headers=headers; this.length=length; this.body=body;
        }
    }
    private final SSLServerSocket listener;
    private final Handler handler;
    private final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8));
    private volatile boolean closed;
    public LocalHttpsServer(SSLServerSocketFactory factory, Handler handler) throws IOException {
        this.handler=handler;
        listener=(SSLServerSocket)factory.createServerSocket(0,16);
        listener.setEnabledProtocols(new String[]{"TLSv1.2"});
    }
    public int port() { return listener.getLocalPort(); }
    public void run() {
        while(!closed) {
            try {
                SSLSocket socket=(SSLSocket)listener.accept();
                if (!socket.getInetAddress().isSiteLocalAddress() && !socket.getInetAddress().isLoopbackAddress()
                        && !socket.getInetAddress().isLinkLocalAddress()) { socket.close(); continue; }
                socket.setSoTimeout(30_000);
                clients.add(socket);
                try { workers.execute(()->serve(socket)); }
                catch(RejectedExecutionException e) { clients.remove(socket); socket.close(); }
            } catch(IOException e) { if(!closed) close(); }
        }
    }
    private void serve(SSLSocket socket) {
        try (socket) {
            socket.startHandshake();
            InputStream in=new BufferedInputStream(socket.getInputStream(),65536);
            OutputStream out=socket.getOutputStream();
            Request request=parse(in);
            try { handler.handle(request,out); }
            catch(Exception e) { /* Do not append an error after a partially written download. */ }
        } catch(Exception ignored) {
            // Malformed requests and disconnected clients do not terminate the listener.
        } finally { clients.remove(socket); }
    }
    static Request parse(InputStream in) throws IOException {
        String[] first=line(in,4096).split(" ",-1);
        if(first.length!=3 || !first[2].equals("HTTP/1.1") || !first[0].matches("GET|POST|PUT|DELETE")
                || !first[1].matches("/[a-zA-Z0-9/_-]*")) throw new IOException("Invalid request");
        Map<String,String> headers=new HashMap<>();
        int total=0;
        for(int n=0;;n++) {
            String line=line(in,4096); total+=line.length();
            if(total>16384 || n>40) throw new IOException("Headers too large");
            if(line.isEmpty()) break;
            int colon=line.indexOf(':');
            if(colon<=0) throw new IOException("Invalid header");
            String key=line.substring(0,colon).toLowerCase(Locale.ROOT);
            if(!key.matches("[a-z0-9-]+") || headers.put(key,line.substring(colon+1).trim())!=null)
                throw new IOException("Duplicate or invalid header");
        }
        if(headers.containsKey("transfer-encoding") || headers.containsKey("expect")) throw new IOException("Unsupported framing");
        String host=headers.get("host");
        if(host==null || !host.matches("[0-9.]+:[0-9]+|\\[[0-9a-fA-F:%]+\\]:[0-9]+")) throw new IOException("Numeric local host required");
        String origin=headers.get("origin");
        if(origin!=null && !origin.equals("https://"+host)) throw new IOException("Cross origin denied");
        String raw=headers.getOrDefault("content-length","0");
        if(!raw.matches("[0-9]{1,10}")) throw new IOException("Invalid length");
        long length=Long.parseLong(raw);
        if(length>IncomingTransfer.MAX_CHUNK) throw new IOException("Body too large");
        return new Request(first[0],first[1],Collections.unmodifiableMap(headers),length,new Limited(in,length));
    }
    private static String line(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(int n=0;n<limit;n++) {
            int c=in.read(); if(c<0) throw new EOFException();
            if(c=='\r') { if(in.read()!='\n') throw new IOException("Invalid line"); return bytes.toString("US-ASCII"); }
            if(c<32 || c>126) throw new IOException("Invalid header character");
            bytes.write(c);
        }
        throw new IOException("Line too long");
    }
    public static void headers(OutputStream out,int status,String type,long length) throws IOException {
        out.write(("HTTP/1.1 "+status+" Response\r\nContent-Type: "+type+"\r\nContent-Length: "+length+
            "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"+
            "Referrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    }
    public static void reply(OutputStream out,int status,String type,byte[] bytes) throws IOException {
        headers(out,status,type,bytes.length); out.write(bytes); out.flush();
    }
    public void close() {
        closed=true;
        try { listener.close(); } catch(IOException ignored) {}
        for(Socket s:clients) try{s.close();}catch(IOException ignored){}
        workers.shutdownNow();
    }
    private static final class Limited extends InputStream {
        private final InputStream input; private long left;
        Limited(InputStream in,long size){input=in;left=size;}
        public int read() throws IOException {byte[] b=new byte[1]; int n=read(b,0,1);return n<0?-1:b[0]&255;}
        public int read(byte[] b,int off,int len)throws IOException {
            if(left==0)return -1;
            int n=input.read(b,off,(int)Math.min(len,left));
            if(n<0)throw new EOFException(); left-=n; return n;
        }
    }
}
