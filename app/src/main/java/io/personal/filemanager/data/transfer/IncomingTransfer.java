package io.personal.filemanager.data.transfer;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.UUID;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

/** One explicitly accepted file. Owned by the service, never constructed from a remote path.
 * Staging and destination directories must be private to the app (no untrusted writers).
 * Resume is deliberately limited to this live object; a process restart needs fresh consent.
 */
public final class IncomingTransfer implements AutoCloseable {
    public static final int MAX_CHUNK = 4 * 1024 * 1024;
    private final Path staging;
    private final Path destination;
    private final String name;
    private final long size;
    private final byte[] expectedHash;
    private long offset;
    private boolean closed;
    private final boolean grouped;
    private boolean renameOnConflict;

    public IncomingTransfer(Path privateStaging, Path privateDestination, String name,
                            long size, String sha256, long acceptedLimit) throws IOException {
        this(privateStaging, privateDestination, name, size, sha256, acceptedLimit, false);
    }
    public IncomingTransfer(Path privateStaging, Path privateDestination, String name,
                            long size, String sha256, long acceptedLimit, boolean grouped) throws IOException {
        this.grouped = grouped;
        validatePath(name);
        if (size < 0 || acceptedLimit < 0 || size > acceptedLimit)
            throw new IOException("File exceeds accepted size");
        this.expectedHash = parseHash(sha256);
        this.name = name;
        this.size = size;
        Files.createDirectories(privateStaging);
        Files.createDirectories(privateDestination);
        this.destination = privateDestination.toRealPath();
        this.staging = Files.createTempFile(privateStaging.toRealPath(), "incoming-", ".part");
    }

    /** Shared «Загрузки/Передача»: an existing name gets « (1)», « (2)»… instead of failing; nothing is overwritten. */
    public IncomingTransfer renameOnConflict() { renameOnConflict = true; return this; }

    private static Path freeName(Path file) {
        String full = file.getFileName().toString();
        int dot = full.lastIndexOf('.');
        String base = dot > 0 ? full.substring(0, dot) : full, ext = dot > 0 ? full.substring(dot) : "";
        for (int n = 1; n < 10_000; n++) {
            Path candidate = file.resolveSibling(base + " (" + n + ")" + ext);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
        }
        return file;
    }

    public synchronized long offset() { return offset; }
    public long size() { return size; }

    /** The sender must supply the exact acknowledged offset and SHA-256 of this chunk.
     * Failed, short, or corrupt chunks never advance the acknowledged offset.
     * Caller must enforce a socket read deadline and authenticate before calling this method.
     */
    public synchronized long append(long at, int length, String sha256, InputStream input)
            throws IOException {
        ensureOpen();
        if (at != offset || length <= 0 || length > MAX_CHUNK || length > size - offset)
            throw new IOException("Invalid offset or chunk size");
        byte[] hash = parseHash(sha256);
        Path chunk = Files.createTempFile(staging.getParent(), "chunk-", ".part");
        try {
            MessageDigest digest = digest();
            try (OutputStream out = Files.newOutputStream(chunk)) {
                byte[] buffer = new byte[64 * 1024];
                int left = length;
                while (left > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    int count = input.read(buffer, 0, Math.min(left, buffer.length));
                    if (count < 0) throw new EOFException("Incomplete chunk");
                    if (count == 0) continue;
                    digest.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                    left -= count;
                }
            }
            if (!MessageDigest.isEqual(hash, digest.digest())) throw new IOException("Chunk hash mismatch");
            try (RandomAccessFile out = new RandomAccessFile(staging.toFile(), "rw");
                 InputStream in = Files.newInputStream(chunk)) {
                out.setLength(offset); // discard any unacknowledged write from an earlier I/O failure
                out.seek(offset);
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.getFD().sync();
                offset += length;
            }
            return offset;
        } finally {
            Files.deleteIfExists(chunk);
        }
    }

    /** Verify the entire file before publishing. A new directory isolates each accepted file;
     * a repeated filename cannot overwrite an earlier delivery. No archive extraction occurs.
     */
    public synchronized Path finish() throws IOException {
        ensureOpen();
        if (offset != size || Files.size(staging) != size) throw new IOException("Incomplete file");
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(staging)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                digest.update(buffer, 0, count);
            }
        }
        if (!MessageDigest.isEqual(expectedHash, digest.digest())) throw new IOException("File hash mismatch");
        Path delivery = grouped ? destination : Files.createDirectory(destination.resolve(UUID.randomUUID().toString()));
        Path file = delivery.resolve(name);
        Files.createDirectories(file.getParent());
        try {
            // Both directories must be on the same filesystem. Fail closed if atomic move unsupported.
            synchronized (IncomingTransfer.class) {
                // Destination is app-private; serialize all publishers to avoid replacing duplicate paths.
                if (renameOnConflict && Files.exists(file, LinkOption.NOFOLLOW_LINKS)) file = freeName(file);
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(name);
                Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            // Keep an empty isolated directory on failure; never recursively delete outside staging.
            throw e;
        }
        closed = true;
        return file;
    }

    /** Cancel releases partial contents. Safe to call more than once. */
    @Override public synchronized void close() throws IOException {
        Files.deleteIfExists(staging);
        closed = true;
    }
    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Transfer closed");
    }
    public static void validatePath(String path) throws IOException {
        if (path == null || path.length() > 2000) throw new IOException("Invalid relative path");
        String[] parts = path.split("/", -1);
        if (parts.length > 16) throw new IOException("Folder depth limit");
        for (String part : parts) validateName(part);
    }
    public static void validateName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.getBytes(StandardCharsets.UTF_8).length > 180 || name.equals(".") || name.equals("..")
                || name.endsWith(".") || name.endsWith(" ")) throw new IOException("Invalid filename");
        String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) throw new IOException("Reserved filename");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || "/\\:*?\"<>|".indexOf(c) >= 0)
                throw new IOException("Invalid filename");
        }
    }
    private static byte[] parseHash(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) throw new IOException("Invalid SHA-256");
        byte[] result = new byte[32];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i*2, i*2+2), 16);
        return result;
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
