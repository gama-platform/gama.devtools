package gama.ui.devtools.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;

import gama.ui.devtools.GamaDevToolsActivator;

/** Reuses only processor exports whose saved inputs and archive content are unchanged. */
public final class ProcessorReuse {

	private static final List<String> MODULES = List.of("gama.annotations", "gama.processor");
	private static final Set<String> EXCLUDED = Set.of(".git", "bin", "target", "gama.processor.jar");

	private ProcessorReuse() {}

	public static String inputs(Path source, BooleanSupplier canceled) throws IOException {
		var digest = digest();
		for (String module : MODULES) {
			Path root = source.resolve(module);
			try (var paths = Files.walk(root)) {
				for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
					LocalReleaseBuilder.checkCanceled(canceled);
					Path relative = root.relativize(file);
					boolean excluded = false;
					for (Path part : relative) {
						if (EXCLUDED.contains(part.toString())) excluded = true;
					}
					if (excluded) continue;
					digest.update((module + "/" + relative.toString().replace('\\', '/') + "\0")
							.getBytes(StandardCharsets.UTF_8));
					hashFile(digest, file);
				}
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	public static void record(Path source, String before) throws IOException {
		String after = inputs(source, () -> false);
		if (!before.equals(after)) throw new IOException(
				"Processor sources changed during export. Rebuild the processor again before reusing it for releases.");
		Files.writeString(stamp(source), after + "\n" + archiveHash(source));
	}

	public static boolean prepare(Path source, Path snapshot, BooleanSupplier canceled) throws IOException {
		Path stamp = stamp(source);
		if (!Files.isRegularFile(stamp)) return false;
		String expected = Files.readString(stamp);
		if (!Files.isRegularFile(source.resolve("gama.processor/gama.processor.jar"))
				|| !expected.equals(inputs(snapshot, canceled) + "\n" + archiveHash(snapshot))) return false;
		int annotations = 0;
		int processor = 0;
		try (var zip = new ZipFile(snapshot.resolve("gama.processor/gama.processor.jar").toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				LocalReleaseBuilder.checkCanceled(canceled);
				var entry = entries.nextElement();
				if (entry.isDirectory()) continue;
				String name = entry.getName();
				String module;
				if (name.startsWith("gama/annotations/")) {
					module = "gama.annotations";
					if (name.endsWith(".class")) annotations++;
				} else if (name.startsWith("gama/processor/")
						|| name.equals("META-INF/services/javax.annotation.processing.Processor")) {
					module = "gama.processor";
					if (name.endsWith(".class")) processor++;
				} else continue;
				Path output = snapshot.resolve(module + "/target/classes");
				Path file = output.resolve(name).normalize();
				if (!file.startsWith(output)) throw new IOException("Invalid entry in processor archive: " + name);
				Files.createDirectories(file.getParent());
				try (var input = zip.getInputStream(entry)) { Files.copy(input, file); }
			}
		}
		if (annotations == 0 || processor == 0) throw new IOException(
				"The verified processor archive does not contain both annotation and processor classes.");
		return true;
	}

	private static Path stamp(Path source) throws IOException {
		Path directory = GamaDevToolsActivator.getInstance().getStateLocation().toPath().resolve("processor-fingerprints");
		Files.createDirectories(directory);
		return directory.resolve(HexFormat.of().formatHex(digest().digest(
				source.toRealPath().toString().getBytes(StandardCharsets.UTF_8))) + ".txt");
	}

	private static String archiveHash(Path source) throws IOException {
		var digest = digest();
		hashFile(digest, source.resolve("gama.processor/gama.processor.jar"));
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void hashFile(MessageDigest digest, Path file) throws IOException {
		try (var input = Files.newInputStream(file)) {
			byte[] buffer = new byte[65536];
			for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
		}
	}

	private static MessageDigest digest() {
		try { return MessageDigest.getInstance("SHA-256"); }
		catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
}
