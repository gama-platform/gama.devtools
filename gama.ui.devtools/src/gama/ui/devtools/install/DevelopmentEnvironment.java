/*******************************************************************************************************
 *
 * DevelopmentEnvironment.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and
 * simulation platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.install;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.VMStandin;
import org.eclipse.jdt.launching.environments.IExecutionEnvironment;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.ImportPackageSpecification;
import org.eclipse.osgi.service.resolver.VersionConstraint;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.osgi.framework.Version;

import gama.ui.devtools.launch.GamaLaunches;
import gama.ui.devtools.util.ResourceUtils;

/** Checks the Eclipse installation used to develop GAMA, and detects and registers JDK 25 installations. */
public final class DevelopmentEnvironment {

	public enum Level { OK, WARNING, ERROR }

	/** A requirement; {@code detail} may contain {@code <a href="...">} links (see the Get started wizard). */
	public record Check(String title, Level level, String detail) {}

	/** A JDK 25 found on disk but not yet registered in Eclipse. */
	public record DetectedJdk(Path home, String version, String vendor) {
		public String label() {
			return "JDK " + version + (vendor.isBlank() ? "" : " (" + vendor + ")") + " - " + home;
		}
	}

	public static final String RELEASE = "2026-03";
	public static final Version MINIMUM_PLATFORM = new Version(4, 39, 0);
	public static final String RELEASE_SITE = "https://download.eclipse.org/releases/" + RELEASE + "/";
	public static final String ECLIPSE_DOWNLOAD = "https://www.eclipse.org/downloads/packages/release/" + RELEASE
			+ "/r/eclipse-ide-java-and-dsl-developers";
	public static final String JDK_DOWNLOAD = "https://adoptium.net/temurin/releases/?version=25";
	/** Opens Help > Install New Software... */
	public static final String INSTALL_SOFTWARE = "p2:install";
	public static final List<String> EXECUTION_ENVIRONMENTS = List.of("JavaSE-25", "JavaSE-21");
	private static final String STANDARD_VM_TYPE = "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType";
	private static final long MINIMUM_HEAP = 1900L * 1024 * 1024;

	private DevelopmentEnvironment() {}

	/** Requirements of the running Eclipse itself, available before any GAMA source is installed. */
	public static List<Check> eclipseChecks() {
		List<Check> checks = new ArrayList<>();
		Version platform = platformVersion();
		String download = "<a href=\"" + ECLIPSE_DOWNLOAD + "\">Download Eclipse IDE for Java and DSL Developers "
				+ RELEASE + "</a>";
		if (platform == null) {
			checks.add(new Check("Eclipse version", Level.WARNING,
					"Cannot determine the Eclipse platform version. GAMA requires Eclipse " + RELEASE + " or newer. "
							+ download + "."));
		} else if (platform.compareTo(MINIMUM_PLATFORM) < 0) {
			checks.add(new Check("Eclipse version", Level.ERROR, "This is Eclipse " + releaseName(platform) + " ("
					+ platform.getMajor() + "." + platform.getMinor() + "). GAMA requires " + RELEASE
					+ " or newer. " + download + ", then install these tools in it."));
		} else {
			checks.add(new Check("Eclipse version", Level.OK,
					"Eclipse " + releaseName(platform) + " (" + platform.getMajor() + "." + platform.getMinor() + ")."));
		}

		int java = Runtime.version().feature();
		checks.add(java >= 21 ? new Check("Java running Eclipse", Level.OK, "Java " + Runtime.version() + ".")
				: new Check("Java running Eclipse", Level.ERROR, "Eclipse runs on Java " + java
						+ "; Java 21 or newer is required. " + download + " (it bundles a suitable Java runtime)."));

		String install = "Use <a href=\"" + INSTALL_SOFTWARE + "\">Help > Install New Software...</a> with "
				+ "<a href=\"" + RELEASE_SITE + "\">" + RELEASE_SITE + "</a>";
		checks.add(component("Java development tools (JDT)", List.of("org.eclipse.jdt.core", "org.eclipse.jdt.ui"),
				install + " and install \"Eclipse Java Development Tools\", or " + download + "."));
		checks.add(component("Plug-in Development Environment (PDE)",
				List.of("org.eclipse.pde.core", "org.eclipse.pde.ui"),
				install + " and install \"Eclipse Plug-in Development Environment\"."));
		checks.add(component("Xtext and MWE2 (GAML grammar)",
				List.of("org.eclipse.xtext.ui", "org.eclipse.xtext.xtext.generator", "org.eclipse.emf.mwe2.launch"),
				install + " and install \"Xtext Complete SDK\" (category Modeling), or " + download + "."));

		long heap = Runtime.getRuntime().maxMemory();
		checks.add(heap == Long.MAX_VALUE || heap >= MINIMUM_HEAP
				? new Check("Eclipse memory", Level.OK, "Maximum heap: " + heap / (1024 * 1024) + " MB.")
				: new Check("Eclipse memory", Level.WARNING, "Maximum heap is " + heap / (1024 * 1024)
						+ " MB; building GAMA needs at least 2 GB. Set -Xmx2048m (or more) after -vmargs in "
						+ "eclipse.ini, then restart Eclipse."));
		return checks;
	}

	/** Requirements on the Java installations registered in Eclipse. */
	public static List<Check> jdkChecks() {
		List<Check> checks = new ArrayList<>();
		List<IVMInstall> jdks = GamaLaunches.jdks();
		if (jdks.isEmpty()) {
			checks.add(new Check("JDK 25", Level.ERROR, "No JDK 25 is registered in Eclipse. Register one below, or "
					+ "<a href=\"" + JDK_DOWNLOAD + "\">download Eclipse Temurin 25</a> (a full JDK, not a JRE)."));
			return checks;
		}
		checks.add(new Check("JDK 25", Level.OK, "Registered: "
				+ String.join(", ", jdks.stream().map(IVMInstall::getName).toList()) + "."));
		IVMInstall defaultVm = JavaRuntime.getDefaultVMInstall();
		checks.add(jdks.contains(defaultVm) ? new Check("Default JRE", Level.OK, defaultVm.getName() + ".")
				: new Check("Default JRE", Level.WARNING, "The workspace default JRE is "
						+ (defaultVm == null ? "undefined" : defaultVm.getName()) + ", not a JDK 25."));
		for (String id : EXECUTION_ENVIRONMENTS) {
			IExecutionEnvironment environment = JavaRuntime.getExecutionEnvironmentsManager().getEnvironment(id);
			IVMInstall vm = environment == null ? null : environment.getDefaultVM();
			if (environment == null) {
				checks.add(new Check(id, Level.ERROR, "This Eclipse does not know the " + id
						+ " execution environment. Use Eclipse " + RELEASE + " or newer."));
			} else if (vm != null && jdks.contains(vm)) {
				checks.add(new Check(id, Level.OK, "Uses " + vm.getName() + "."));
			} else {
				checks.add(new Check(id, Level.WARNING, (vm == null ? "No JRE is associated" : vm.getName()
						+ " is associated") + "; GAMA projects expect a JDK 25 for " + id + "."));
			}
		}
		return checks;
	}

	/** Whether a JDK 25 is registered, is the default JRE and is associated with the execution environments. */
	public static boolean jdkConfigured() {
		return jdkChecks().stream().allMatch(check -> check.level() == Level.OK);
	}

	/** JDK 25 installations found in the usual locations and not already registered in Eclipse. */
	public static List<DetectedJdk> detectJdks() {
		Set<Path> registered = new LinkedHashSet<>();
		for (IVMInstallType type : JavaRuntime.getVMInstallTypes()) {
			for (IVMInstall vm : type.getVMInstalls()) {
				if (vm.getInstallLocation() != null) registered.add(canonical(vm.getInstallLocation().toPath()));
			}
		}
		Map<Path, DetectedJdk> found = new LinkedHashMap<>();
		for (Path home : candidateHomes()) {
			DetectedJdk jdk = inspect(canonical(home));
			if (jdk == null) continue;
			Path path = canonical(jdk.home());
			if (!registered.contains(path)) found.putIfAbsent(path, new DetectedJdk(path, jdk.version(), jdk.vendor()));
		}
		return new ArrayList<>(found.values());
	}

	/** Reads the {@code release} file of a JDK home, or of its {@code Contents/Home} on macOS. */
	public static DetectedJdk inspect(final Path location) {
		for (Path home : List.of(location, location.resolve("Contents/Home"))) {
			Path release = home.resolve("release");
			boolean javac = Files.isRegularFile(home.resolve("bin/javac"))
					|| Files.isRegularFile(home.resolve("bin/javac.exe"));
			if (!javac || !Files.isRegularFile(release)) continue;
			Properties properties = new Properties();
			try (var reader = Files.newBufferedReader(release)) {
				properties.load(reader);
			} catch (IOException | IllegalArgumentException e) {
				continue;
			}
			String version = unquote(properties.getProperty("JAVA_VERSION", ""));
			if (version.matches("25(?:[.+\\-].*)?")) {
				return new DetectedJdk(home, version, unquote(properties.getProperty("IMPLEMENTOR", "")));
			}
		}
		return null;
	}

	/** Registers a JDK as a standard VM install (unless already registered) and returns it. */
	public static IVMInstall register(final DetectedJdk jdk) throws CoreException {
		IVMInstallType type = JavaRuntime.getVMInstallType(STANDARD_VM_TYPE);
		if (type == null) throw new CoreException(ResourceUtils.error("Eclipse has no standard JRE type.", null));
		File home = jdk.home().toFile();
		for (IVMInstall vm : type.getVMInstalls()) {
			if (vm.getInstallLocation() != null
					&& canonical(vm.getInstallLocation().toPath()).equals(canonical(jdk.home()))) return vm;
		}
		var status = type.validateInstallLocation(home);
		if (!status.isOK()) throw new CoreException(status);
		long id = System.currentTimeMillis();
		while (type.findVMInstall(String.valueOf(id)) != null) id++;
		String base = "JDK " + jdk.version() + (jdk.vendor().isBlank() ? "" : " (" + jdk.vendor() + ")"), name = base;
		for (int i = 2; type.findVMInstallByName(name) != null; i++) name = base + " " + i;
		VMStandin standin = new VMStandin(type, String.valueOf(id));
		standin.setName(name);
		standin.setInstallLocation(home);
		IVMInstall vm = standin.convertToRealVM();
		JavaRuntime.saveVMConfiguration();
		return vm;
	}

	/** Makes the JDK the default JRE and/or associates it with {@link #EXECUTION_ENVIRONMENTS}. */
	public static void configure(final IVMInstall vm, final boolean makeDefault, final boolean environments,
			final IProgressMonitor monitor) throws CoreException {
		if (environments) {
			for (String id : EXECUTION_ENVIRONMENTS) {
				IExecutionEnvironment environment = JavaRuntime.getExecutionEnvironmentsManager().getEnvironment(id);
				if (environment != null) environment.setDefaultVM(vm);
			}
		}
		if (makeDefault) JavaRuntime.setDefaultVMInstall(vm, monitor, true);
		else JavaRuntime.saveVMConfiguration();
	}

	/**
	 * Bundles and packages required by the workspace GAMA plugins that neither the workspace nor the target platform
	 * provide, by requiring plugin.
	 */
	public static Map<String, Set<String>> unresolvedRequirements() {
		Map<String, Set<String>> result = new LinkedHashMap<>();
		for (var model : PluginRegistry.getWorkspaceModels()) {
			BundleDescription description = model.getBundleDescription();
			if (description == null || description.isResolved()) continue;
			String id = description.getSymbolicName();
			if (id == null || !id.startsWith("gama.") && !id.startsWith("gaml.")) continue;
			var state = description.getContainingState();
			if (state == null) continue;
			for (VersionConstraint constraint : state.getStateHelper().getUnsatisfiedConstraints(description)) {
				String name = constraint.getName();
				if (name == null) continue;
				if (constraint instanceof ImportPackageSpecification) {
					result.computeIfAbsent(id, k -> new LinkedHashSet<>()).add("package " + name);
				} else if (PluginRegistry.findModel(name) == null) {
					result.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(name);
				}
			}
		}
		return result;
	}

	static String releaseName(final Version platform) {
		if (platform.getMajor() != 4 || platform.getMinor() < 31) return platform.toString();
		int index = platform.getMinor() - 31;
		return 2024 + index / 4 + "-" + List.of("03", "06", "09", "12").get(index % 4);
	}

	private static Version platformVersion() {
		var bundle = Platform.getBundle("org.eclipse.platform");
		if (bundle != null) return bundle.getVersion();
		String build = System.getProperty("eclipse.buildId", "");
		var matcher = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)").matcher(build);
		return matcher.find() ? new Version(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), 0)
				: null;
	}

	private static Check component(final String title, final List<String> bundles, final String fix) {
		List<String> missing = bundles.stream().filter(id -> Platform.getBundle(id) == null).toList();
		return missing.isEmpty() ? new Check(title, Level.OK, "Installed.")
				: new Check(title, Level.ERROR, "Missing " + String.join(", ", missing) + ". " + fix
						+ " Restart Eclipse afterwards.");
	}

	private static List<Path> candidateHomes() {
		List<Path> roots = new ArrayList<>();
		Path user = Path.of(System.getProperty("user.home"));
		String os = Platform.getOS();
		if (Platform.OS_MACOSX.equals(os)) {
			roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
			roots.add(user.resolve("Library/Java/JavaVirtualMachines"));
			roots.add(Path.of("/opt/homebrew/opt"));
			roots.add(Path.of("/usr/local/opt"));
		} else if (Platform.OS_WIN32.equals(os)) {
			for (String variable : List.of("ProgramFiles", "ProgramW6432", "LOCALAPPDATA")) {
				String value = System.getenv(variable);
				if (value == null) continue;
				for (String vendor : List.of("Java", "Eclipse Adoptium", "Zulu", "Microsoft", "Amazon Corretto",
						"BellSoft", "Semeru", "Programs\\Eclipse Adoptium")) {
					roots.add(Path.of(value, vendor));
				}
			}
		} else {
			roots.add(Path.of("/usr/lib/jvm"));
			roots.add(Path.of("/usr/java"));
			roots.add(Path.of("/opt"));
		}
		roots.add(user.resolve(".sdkman/candidates/java"));
		roots.add(user.resolve(".jdks"));
		roots.add(user.resolve(".asdf/installs/java"));
		List<Path> homes = new ArrayList<>();
		for (String variable : List.of("JAVA_HOME", "JDK_HOME")) {
			String value = System.getenv(variable);
			if (value != null && !value.isBlank()) homes.add(Path.of(value));
		}
		for (Path root : roots) {
			if (!Files.isDirectory(root)) continue;
			try (Stream<Path> children = Files.list(root)) {
				children.filter(Files::isDirectory).forEach(child -> {
					homes.add(child);
					// Homebrew: opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
					homes.add(child.resolve("libexec/openjdk.jdk"));
				});
			} catch (IOException | SecurityException e) {
				// Unreadable locations are simply not searched.
			}
		}
		return homes;
	}

	private static Path canonical(final Path path) {
		try {
			return path.toRealPath();
		} catch (IOException | SecurityException e) {
			return path.toAbsolutePath().normalize();
		}
	}

	private static String unquote(final String value) {
		String result = value.strip();
		if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
			result = result.substring(1, result.length() - 1);
		}
		return result.toLowerCase(Locale.ROOT).equals("null") ? "" : result;
	}
}
