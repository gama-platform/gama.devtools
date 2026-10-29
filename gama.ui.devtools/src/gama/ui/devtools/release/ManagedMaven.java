package gama.ui.devtools.release;

import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

/** A checksum-pinned private Maven distribution; never invokes an installed mvn or a shell. */
public final class ManagedMaven {

	public static final String VERSION = "3.9.16";
	private static final String SHA512 = "ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929"
			+ "ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3";
	private static final URI DISTRIBUTION = URI.create("https://repo.maven.apache.org/maven2/org/apache/maven/"
			+ "apache-maven/" + VERSION + "/apache-maven-" + VERSION + "-bin.zip");

	private ManagedMaven() {}

	public static Path prepare(Path cache, BooleanSupplier canceled, Consumer<String> progress) throws Exception {
		Files.createDirectories(cache);
		try (var channel = FileChannel.open(cache.resolve("maven.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			var lock = channel.tryLock();
			if (lock == null) throw new IOException("Another local release is preparing Maven. Try again when it finishes.");
			try (lock) {
				Path home = cache.resolve("apache-maven-" + VERSION);
				Path archive = cache.resolve("apache-maven-" + VERSION + "-bin.zip");
				if (!Files.isRegularFile(archive)) {
					progress.accept("Downloading Apache Maven " + VERSION + " (first use only)");
					Path download = Files.createTempFile(cache, "maven-", ".zip.part");
					try {
						URLConnection connection = DISTRIBUTION.toURL().openConnection();
						connection.setConnectTimeout(15_000);
						connection.setReadTimeout(15_000);
						try (var input = connection.getInputStream(); var output = Files.newOutputStream(download)) {
							byte[] buffer = new byte[64 * 1024];
							for (int size; (size = input.read(buffer)) >= 0;) {
								LocalReleaseBuilder.checkCanceled(canceled);
								output.write(buffer, 0, size);
							}
						}
						verify(download, canceled);
						Files.move(download, archive);
					} finally {
						Files.deleteIfExists(download);
					}
				} else {
					try {
						verify(archive, canceled);
					} catch (IOException e) {
						Files.delete(archive);
						throw new IOException("The cached Maven download was corrupt and has been removed. Retry the build.", e);
					}
				}
				if (Files.isRegularFile(home.resolve(".complete")) && Files.isRegularFile(home.resolve("bin/m2.conf"))) return home;
				LocalReleaseBuilder.deleteTree(home);
				progress.accept("Preparing Apache Maven " + VERSION);
				extract(archive, cache, canceled);
				if (!Files.isRegularFile(home.resolve("bin/m2.conf"))) throw new IOException("Incomplete Maven distribution.");
				Files.writeString(home.resolve(".complete"), SHA512);
				return home;
			}
		}
	}

	private static void verify(Path archive, BooleanSupplier canceled) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-512");
		try (var input = Files.newInputStream(archive)) {
			byte[] buffer = new byte[64 * 1024];
			for (int size; (size = input.read(buffer)) >= 0;) {
				LocalReleaseBuilder.checkCanceled(canceled);
				digest.update(buffer, 0, size);
			}
		}
		if (!HexFormat.of().formatHex(digest.digest()).equals(SHA512)) {
			throw new IOException("Apache Maven " + VERSION + " failed SHA-512 verification.");
		}
	}

	private static void extract(Path archive, Path cache, BooleanSupplier canceled) throws IOException {
		Path home = cache.resolve("apache-maven-" + VERSION).toAbsolutePath().normalize();
		try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
			for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				LocalReleaseBuilder.checkCanceled(canceled);
				Path target = cache.resolve(entry.getName()).toAbsolutePath().normalize();
				if (!target.startsWith(home)) throw new IOException("Unsafe entry in Maven distribution: " + entry.getName());
				if (entry.isDirectory()) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					try (var output = Files.newOutputStream(target)) {
						byte[] buffer = new byte[64 * 1024];
						for (int size; (size = zip.read(buffer)) >= 0;) {
							LocalReleaseBuilder.checkCanceled(canceled);
							output.write(buffer, 0, size);
						}
					}
				}
			}
		}
	}
}
