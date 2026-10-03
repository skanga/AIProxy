package com.aiproxy.provider.codex.auth.nativeoauth;

import com.aiproxy.util.Json;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Short cross-process transactions serialize rotating refreshes and login/logout commits. */
public final class NativeCredentialStore {
    private final Path path;
    public NativeCredentialStore(Path path) { this.path = path.toAbsolutePath().normalize(); }
    public Path path() { return path; }
    public String status() throws Exception {
        return locked(() -> {
            NativeCredential credential = read();
            return credential == null ? "not found" : "account " + credential.subject() + ", client " + credential.clientId()
                    + ", access expires " + java.time.Instant.ofEpochSecond(credential.expiresAt());
        });
    }
    Path sibling(String suffix) { return path.resolveSibling(path.getFileName() + suffix); }
    @FunctionalInterface public interface Transaction<T> { T run() throws Exception; }
    public <T> T locked(Transaction<T> transaction) throws Exception {
        Files.createDirectories(path.getParent());
        Path lockPath = sibling(".lock");
        rejectLink(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            protect(lockPath);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(40).toNanos();
            while (true) {
                java.nio.channels.FileLock lease = null;
                try { lease = channel.tryLock(); } catch (OverlappingFileLockException busy) { /* another thread */ }
                if (lease != null) {
                    try { return transaction.run(); } finally { lease.release(); }
                }
                if (System.nanoTime() >= deadline) throw new IOException("Native credentials are busy; retry shortly");
                Thread.sleep(25);
            }
        }
    }
    NativeCredential read() throws IOException {
        JsonNode node = readJson(path);
        return node == null ? null : NativeCredential.parse(node);
    }
    JsonNode registration() throws IOException { return readJson(sibling(".registration")); }
    String logoutGeneration() throws IOException {
        JsonNode generation = readJson(sibling(".generation"));
        return generation == null ? "" : NativeCredential.text(generation,"generation");
    }
    String hostId() throws IOException {
        Path hostPath = sibling(".host");
        JsonNode existing = readJson(hostPath);
        if (existing != null) return NativeCredential.text(existing, "host_id");
        String host = "urn:uuid:" + UUID.randomUUID();
        write(hostPath, Json.MAPPER.createObjectNode().put("host_id", host));
        return host;
    }
    void save(NativeCredential credential) throws IOException { write(path, credential.json()); }
    void archiveRegistration() throws IOException {
        NativeCredential active = read();
        JsonNode registration = active == null ? registration() : active.registration();
        if (registration != null) {
            // Keep the prior account/client mapping, never its tokens, when adding a new registration.
            String clientId = NativeCredential.text(registration,"client_id");
            String name = UUID.nameUUIDFromBytes(clientId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            write(sibling(".registration-" + name),registration);
        }
    }
    void clear(NativeCredential credential) throws IOException {
        if (credential != null) write(sibling(".registration"), credential.registration());
        rejectLink(path);
        // Even logout with no active file must invalidate an in-flight first login.
        write(sibling(".generation"),Json.MAPPER.createObjectNode().put("generation",UUID.randomUUID().toString()));
        Files.deleteIfExists(path);
    }
    private static JsonNode readJson(Path path) throws IOException {
        rejectLink(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        protect(path);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = input.readNBytes(128 * 1024 + 1);
            if (data.length > 128 * 1024) throw new IOException("Native credential file is too large");
            try { return Json.MAPPER.readTree(data); }
            catch (RuntimeException error) { throw new IOException("Invalid native credential JSON"); }
        }
    }
    static void write(Path path, JsonNode value) throws IOException {
        rejectLink(path);
        Path temp = Files.createTempFile(path.getParent(), ".native-auth-", ".tmp");
        try {
            protect(temp); // No secrets are written before permissions are applied.
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(Json.MAPPER.writeValueAsBytes(value));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            // Fail closed on filesystems without atomic replacement.
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static void rejectLink(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Native credential paths must not contain symbolic links");
        }
    }
    static void protect(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("Filesystem cannot protect native credentials");
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
}
