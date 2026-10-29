package gama.ui.devtools.release;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Builds only a private snapshot, publishing a complete runnable folder without overwriting existing releases. */
public final class LocalReleaseBuilder {

	private static final Set<String> GENERATED = Set.of(".git", ".metadata", "target", "bin");

	private LocalReleaseBuilder() {}

	public static Path build(LocalReleasePlan plan, Path state, Path log, BooleanSupplier canceled,
			Consumer<String> progress, Consumer<String> output) throws Exception {
		plan.validate();
		Files.createDirectories(state);
		Path work = Files.createTempDirectory(state, "release-build-");
		Throwable failure = null;
		try {
			Path maven = ManagedMaven.prepare(state.resolve("maven"), canceled, progress);
			Path snapshot = work.resolve("source");
			progress.accept("Copying GAMA sources (excluding Git data and build outputs)");
			copyTree(plan.source(), snapshot, true, canceled);
			checkCanceled(canceled);
			plan.configureSnapshot(snapshot);
			List<List<String>> commands = plan.commands(maven, snapshot);
			for (int i = 0; i < commands.size(); i++) {
				progress.accept("Building " + List.of("gama.annotations", "gama.processor", "gama.parent").get(i)
						+ " for " + plan.target().id());
				run(commands.get(i), snapshot.resolve(List.of("gama.annotations", "gama.processor", "gama.parent").get(i)),
						plan.jdk(), log, canceled, output);
			}
			checkCanceled(canceled);
			progress.accept("Saving release and embedding JDK 25");
			return publish(plan, snapshot, log, canceled);
		} catch (Exception | Error e) {
			failure = e;
			throw e;
		} finally {
			try {
				deleteTree(work);
			} catch (IOException cleanup) {
				if (failure != null) failure.addSuppressed(cleanup);
				else throw cleanup;
			}
		}
	}

	static void run(List<String> command, Path directory, Path jdk, Path log, BooleanSupplier canceled,
			Consumer<String> output) throws Exception {
		checkCanceled(canceled);
		ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
		builder.environment().put("JAVA_HOME", jdk.toString());
		// Avoid inheriting launcher options that can silently change Maven's JDK or build arguments.
		for (String key : List.of("MAVEN_OPTS", "MAVEN_ARGS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
			builder.environment().remove(key);
		}
		Process process = builder.start();
		AtomicReference<IOException> readFailure = new AtomicReference<>();
		Thread reader = Thread.ofVirtual().start(() -> {
			try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
					var writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8,
							java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
				for (String line; (line = input.readLine()) != null;) {
					writer.write(line);
					writer.newLine();
					writer.flush();
					output.accept(line + "\n");
				}
			} catch (IOException e) {
				readFailure.set(e);
				process.destroy();
			}
		});
		try {
			while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
				checkCanceled(canceled);
			}
			reader.join();
			checkCanceled(canceled);
			if (readFailure.get() != null) throw new IOException("Cannot read the Maven build output.", readFailure.get());
			if (process.exitValue() != 0) throw new IOException("Maven failed in " + directory.getFileName()
					+ " (exit " + process.exitValue() + "). See the GAMA Local Release console or " + log);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CancellationException("Local release build interrupted.");
		} finally {
			boolean interrupted = Thread.interrupted();
			if (process.isAlive()) {
				var descendants = process.descendants().toList();
				descendants.reversed().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
				process.onExit().join();
			}
			try {
				reader.join();
			} finally {
				if (interrupted) Thread.currentThread().interrupt();
			}
		}
	}

	static Path publish(LocalReleasePlan plan, Path snapshot, Path log, BooleanSupplier canceled) throws IOException {
		Path product = snapshot.resolve("gama.product/target/products/gama.ui.application.product")
				.resolve(plan.target().directory());
		Path executable = product.resolve(plan.target().mac() ? "Gama.app/Contents/MacOS/Gama"
				: plan.target().windows() ? "Gama.exe" : "Gama");
		if (!Files.isRegularFile(executable)) throw new IOException("The native product was not produced: " + executable);
		Path staging = Files.createTempDirectory(plan.destination(), ".gama-release-");
		try {
			copyTree(product, staging, false, canceled);
			// Cocoa loads the JVM as a bundle: copying only Contents/Home breaks the native launcher.
			Path runtime = staging.resolve(plan.target().mac() ? "Gama.app/Contents/jdk" : "jdk");
			Path runtimeSource = plan.target().mac() ? plan.jdk().getParent().getParent() : plan.jdk();
			copyTree(runtimeSource, runtime, false, canceled);
			if (plan.target().mac()) {
				Files.createSymbolicLink(staging.resolve("Gama.app/Contents/MacOS/jdk"), Path.of("../jdk"));
			}
			Path ini = staging.resolve(plan.target().mac() ? "Gama.app/Contents/Eclipse/Gama.ini" : "Gama.ini");
			configureIni(ini, plan.target());
			if (!plan.target().windows()) {
				Path launcher = staging.resolve(product.relativize(executable));
				if (!launcher.toFile().setExecutable(true, false)) throw new IOException("Cannot make the GAMA launcher executable.");
				Path headless = staging.resolve(plan.target().mac() ? "Gama.app/Contents/headless/gama-headless.sh"
						: "headless/gama-headless.sh");
				if (Files.exists(headless) && !headless.toFile().setExecutable(true, false)) {
					throw new IOException("Cannot make the headless launcher executable.");
				}
			}
			Files.copy(log, staging.resolve(plan.target().mac() ? "Gama.app/Contents/build.log" : "build.log"));
			checkCanceled(canceled);
			Path destination = plan.releasePath();
			if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("A release already exists at " + destination
						+ ". Move it away or choose another destination.");
			}
			Files.move(plan.target().mac() ? staging.resolve("Gama.app") : staging, destination);
			return destination;
		} finally {
			deleteTree(staging);
		}
	}

	static void configureIni(Path ini, LocalReleasePlan.Target target) throws IOException {
		List<String> lines = new ArrayList<>(Files.readAllLines(ini));
		int vmargs = lines.indexOf("-vmargs");
		if (vmargs < 0) throw new IOException("The GAMA launcher configuration has no -vmargs: " + ini);
		for (int i = 0; i < vmargs; i++) {
			if (lines.get(i).equals("-vm")) {
				if (i + 1 >= vmargs) throw new IOException("Invalid -vm in " + ini);
				lines.remove(i);
				lines.remove(i);
				vmargs -= 2;
				i--;
			}
		}
		lines.addAll(0, List.of("-vm", target.mac() ? "./jdk/Contents/Home/bin/java"
				: target.windows() ? "./jdk/bin/javaw.exe" : "./jdk/bin/java"));
		Files.write(ini, lines);
	}

	static void copyTree(Path source, Path target, boolean sourcesOnly, BooleanSupplier canceled) throws IOException {
		Path realSource = source.toRealPath();
		if (target.toAbsolutePath().normalize().startsWith(realSource)) {
			throw new IOException("Cannot copy a directory into itself: " + target);
		}
		Files.walkFileTree(realSource, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
				new SimpleFileVisitor<>() {
					@Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
						checkCanceled(canceled);
						if (sourcesOnly && !directory.equals(realSource) && GENERATED.contains(directory.getFileName().toString())) {
							return FileVisitResult.SKIP_SUBTREE;
						}
						Files.createDirectories(target.resolve(realSource.relativize(directory)));
						return FileVisitResult.CONTINUE;
					}

					@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
						checkCanceled(canceled);
						if (sourcesOnly && GENERATED.contains(file.getFileName().toString())) return FileVisitResult.CONTINUE;
						// Dereference links so the snapshot and bundled JDK never depend on the original installation.
						Files.copy(file, target.resolve(realSource.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
						return FileVisitResult.CONTINUE;
					}
				});
	}

	static void deleteTree(Path directory) throws IOException {
		if (!Files.exists(directory)) return;
		Files.walkFileTree(directory, new SimpleFileVisitor<>() {
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
				if (error != null) throw error;
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	static void checkCanceled(BooleanSupplier canceled) {
		if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
			throw new CancellationException("Local release build canceled.");
		}
	}
}
