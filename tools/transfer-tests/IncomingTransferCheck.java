import io.personal.filemanager.data.transfer.IncomingTransfer;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public class IncomingTransferCheck {
    interface Operation { void run() throws Exception; }
    static int checks;
    static void reject(Operation action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
    static void check(boolean condition) { if (!condition) throw new AssertionError(); checks++; }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("transfer-check-");
        Path staging = root.resolve("staging"), target = root.resolve("received");
        byte[] data = new byte[100_000]; new Random(7).nextBytes(data);
        try {
            for (String name : List.of("../escape", "a/b", "a\\b", "..", "bad\nname", "trailing.", ""))
                reject(() -> IncomingTransfer.validateName(name));
            reject(() -> new IncomingTransfer(staging, target, "file", data.length, hash(data), 10));
            try (IncomingTransfer t = new IncomingTransfer(staging, target, "video.mp4", data.length, hash(data), data.length)) {
                reject(() -> t.append(1, data.length, hash(data), new ByteArrayInputStream(data)));
                reject(() -> t.append(0, data.length, hash(data), new ByteArrayInputStream(new byte[3])));
                check(t.offset() == 0);
                reject(() -> t.append(0, data.length, hash(new byte[0]), new ByteArrayInputStream(data)));
                check(t.offset() == 0);
                byte[] first = Arrays.copyOfRange(data, 0, 50000), last = Arrays.copyOfRange(data, 50000, data.length);
                t.append(0, first.length, hash(first), new ByteArrayInputStream(first));
                reject(t::finish);
                reject(() -> t.append(0, first.length, hash(first), new ByteArrayInputStream(first)));
                t.append(first.length, last.length, hash(last), new ByteArrayInputStream(last));
                Path result = t.finish();
                check(Arrays.equals(Files.readAllBytes(result), data));
                reject(t::finish);
                try (IncomingTransfer duplicate = new IncomingTransfer(staging, target, "video.mp4", 0, hash(new byte[0]), 0)) {
                    Path second = duplicate.finish();
                    check(!second.equals(result));
                    check(Arrays.equals(Files.readAllBytes(result), data));
                }
            }
            try (IncomingTransfer bad = new IncomingTransfer(staging, target, "bad", data.length, hash(new byte[0]), data.length)) {
                bad.append(0, data.length, hash(data), new ByteArrayInputStream(data));
                reject(bad::finish);
            }
            Path batch = target.resolve("folder-batch");
            try (IncomingTransfer nested = new IncomingTransfer(staging, batch, "folder/child.txt", 0, hash(new byte[0]), 0, true)) {
                Path result = nested.finish();
                check(result.equals(batch.toRealPath().resolve("folder/child.txt")));
            }
            try (IncomingTransfer duplicate = new IncomingTransfer(staging, batch, "folder/child.txt", 0, hash(new byte[0]), 0, true)) {
                reject(duplicate::finish);
            }
            reject(() -> IncomingTransfer.validatePath("folder/../escape"));
            reject(() -> IncomingTransfer.validatePath("/absolute"));
            IncomingTransfer cancelled = new IncomingTransfer(staging, target, "cancelled", 0, hash(new byte[0]), 0);
            cancelled.close(); cancelled.close(); reject(cancelled::finish);
            try (var contents = Files.list(staging)) { check(contents.count() == 0); }
            System.out.println("PASS: " + checks + " checks (desktop JVM; not Android or network tests)");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
