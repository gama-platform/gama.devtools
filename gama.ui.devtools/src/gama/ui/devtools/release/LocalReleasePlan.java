package gama.ui.devtools.release;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import gama.ui.devtools.install.InstallModel;

/** GAMA's three-stage build and native-only Tycho configuration, independent of Eclipse. */
public record LocalReleasePlan(Path source, Path jdk, Path destination, Target target) {

	public Path releasePath() { return destination.resolve(target.mac() ? "Gama.app" : "Gama"); }

	public record Target(String os, String ws, String arch) {
		public Target {
			if (!Set.of("macosx/cocoa/x86_64", "macosx/cocoa/aarch64", "win32/win32/x86_64",
					"linux/gtk/x86_64").contains(os + "/" + ws + "/" + arch)) {
				throw new IllegalArgumentException("GAMA does not currently build releases for " + os + "/" + ws + "/" + arch);
			}
		}

		public String directory() { return os + "/" + ws + "/" + arch; }
		public String id() { return os + "." + ws + "." + arch; }
		public boolean mac() { return os.equals("macosx"); }
		public boolean windows() { return os.equals("win32"); }
	}

	public void validate() throws Exception {
		for (String module : List.of("gama.annotations", "gama.processor", "gama.parent", "gama.product")) {
			if (!Files.isRegularFile(source.resolve(module + "/pom.xml"))) {
				throw new IOException("Choose a complete GAMA checkout, including " + module + "/pom.xml.");
			}
		}
		if (!Files.isRegularFile(source.resolve("gama.product/gama.product"))) {
			throw new IOException("The GAMA product definition is missing.");
		}
		validateJdk(jdk, target);
		if (!Files.isDirectory(destination) || !Files.isWritable(destination)) {
			throw new IOException("Choose an existing, writable destination directory.");
		}
		if (destination.toRealPath().startsWith(source.toRealPath())) {
			throw new IOException("Save the release outside the GAMA source checkout.");
		}
		if (Files.exists(releasePath(), LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("A release already exists at " + releasePath()
					+ ". Move it away or choose another destination.");
		}
		Document parent = read(source.resolve("gama.parent/pom.xml"));
		if (!text(parent.getDocumentElement(), "properties", "jdk.version").equals("25")) {
			throw new IOException("This command supports the GAMA build using JDK 25; check gama.parent/pom.xml.");
		}
	}

	public static void validateJdk(Path home, Target target) throws IOException {
		Properties properties = new Properties();
		try (var input = Files.newInputStream(home.resolve("release"))) { properties.load(input); }
		String version = properties.getProperty("JAVA_VERSION", "").replace("\"", "");
		String arch = properties.getProperty("OS_ARCH", "").replace("\"", "");
		if (arch.equals("amd64")) arch = "x86_64";
		if (arch.equals("arm64")) arch = "aarch64";
		if (!version.matches("25(?:[.+\\-].*)?") || !arch.equals(target.arch())
				|| !Files.isRegularFile(home.resolve("bin/java" + (target.windows() ? ".exe" : "")))
				|| !Files.isRegularFile(home.resolve("bin/javac" + (target.windows() ? ".exe" : "")))) {
			throw new IOException("Choose a full JDK 25 for " + target.id() + ", not a JRE or another Java version: " + home);
		}
		if (target.mac() && (!Files.isRegularFile(home.resolve("../Info.plist"))
				|| !Files.isRegularFile(home.resolve("../MacOS/libjli.dylib")))) {
			throw new IOException("Select Contents/Home inside a complete macOS JDK 25 bundle, including Contents/Info.plist.");
		}
	}

	/** Only changes the private snapshot; the source POMs and product stay untouched. */
	public void configureSnapshot(Path snapshot) throws Exception {
		Path parentPath = snapshot.resolve("gama.parent/pom.xml");
		Document parent = read(parentPath);
		Element configuration = plugin(parent, "target-platform-configuration");
		Element environments = child(child(configuration, "configuration"), "environments");
		if (environments == null) throw new IOException("GAMA's Tycho target environments are missing.");
		boolean matched = false;
		for (Element environment : children(environments)) {
			if (text(environment, "os").equals(target.os()) && text(environment, "ws").equals(target.ws())
					&& text(environment, "arch").equals(target.arch())) {
				matched = true;
			} else {
				environments.removeChild(environment);
			}
		}
		if (!matched) throw new IOException("The GAMA POM does not support " + target.id() + ".");
		write(parent, parentPath);

		Path productPath = snapshot.resolve("gama.product/pom.xml");
		Document product = read(productPath);
		Element ant = plugin(product, "maven-antrun-plugin");
		var tasks = ant.getElementsByTagName("target");
		if (tasks.getLength() == 0) throw new IOException("GAMA's product packaging tasks are missing.");
		for (int i = 0; i < tasks.getLength(); i++) {
			for (Element task : children((Element) tasks.item(i))) {
				String output = task.getAttribute("todir");
				if (output.isEmpty()) output = task.getAttribute("dir");
				if (output.contains("/target/products/") && !output.contains("/" + target.directory() + "/")) {
					task.getParentNode().removeChild(task);
				}
			}
		}
		// The folder is delivered directly; do not generate redundant multi-gigabyte archives.
		Element director = plugin(product, "tycho-p2-director-plugin");
		for (Element execution : children(child(director, "executions"))) {
			if (text(execution, "id").equals("archive-products")) execution.getParentNode().removeChild(execution);
		}
		write(product, productPath);

		// Match travis/build.sh: Tycho does not honor the optional Copilot feature.
		Path definitionPath = snapshot.resolve("gama.product/gama.product");
		Document definition = read(definitionPath);
		var features = definition.getElementsByTagName("feature");
		for (int i = features.getLength() - 1; i >= 0; i--) {
			Element feature = (Element) features.item(i);
			if (feature.getAttribute("id").equals("gama.feature.copilot")) feature.getParentNode().removeChild(feature);
		}
		write(definition, definitionPath);
	}

	public List<List<String>> commands(Path mavenHome, Path snapshot) throws IOException {
		Path boot;
		try (var jars = Files.list(mavenHome.resolve("boot"))) {
			var candidates = jars.filter(p -> p.getFileName().toString().matches("plexus-classworlds-.*\\.jar")).toList();
			if (candidates.size() != 1) throw new IOException("Invalid Maven installation: " + mavenHome);
			boot = candidates.getFirst();
		}
		List<List<String>> result = new ArrayList<>();
		for (String module : List.of("gama.annotations", "gama.processor", "gama.parent")) {
			result.add(List.of(jdk.resolve("bin/java" + (target.windows() ? ".exe" : "")).toString(),
					"-Xmx4g", "-Dmaven.home=" + mavenHome,
					"-Dclassworlds.conf=" + mavenHome.resolve("bin/m2.conf"),
					"-Dmaven.multiModuleProjectDirectory=" + snapshot,
					"-cp", boot.toString(), "org.codehaus.plexus.classworlds.launcher.Launcher",
					"--batch-mode", "--no-transfer-progress", "--errors", "-Dstyle.color=never",
					"-f", snapshot.resolve(module + "/pom.xml").toString(),
					"-P", "local-dev,!p2Repo", "-DskipTests", "-Dmaven.jarsigner.skip=true",
					"-Dmaven.build.cache.enabled=false",
					"-Dtycho.targetPlatform=" + target.id(),
					"-Dosgi.os=" + target.os(), "-Dosgi.ws=" + target.ws(), "-Dosgi.arch=" + target.arch(),
					"clean", "install"));
		}
		return result;
	}

	private static Document read(Path file) throws Exception {
		try (InputStream input = Files.newInputStream(file)) { return InstallModel.xml(input); }
	}

	private static void write(Document document, Path file) throws Exception {
		TransformerFactory factory = TransformerFactory.newInstance();
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
		var transformer = factory.newTransformer();
		transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		try (var output = Files.newOutputStream(file)) { transformer.transform(new DOMSource(document), new StreamResult(output)); }
	}

	private static Element plugin(Document document, String id) throws IOException {
		Element plugins = child(child(document.getDocumentElement(), "build"), "plugins");
		for (Element plugin : children(plugins)) {
			if (text(plugin, "artifactId").equals(id)) return plugin;
		}
		throw new IOException("The GAMA POM is missing the " + id + " plugin.");
	}

	private static Element child(Element parent, String name) {
		for (Element element : children(parent)) {
			if (name.equals(element.getLocalName() == null ? element.getTagName() : element.getLocalName())) return element;
		}
		return null;
	}

	private static String text(Element parent, String... names) {
		for (String name : names) parent = child(parent, name);
		return parent == null ? "" : parent.getTextContent().strip();
	}

	private static List<Element> children(Element parent) {
		List<Element> result = new ArrayList<>();
		if (parent != null) {
			for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
				if (node instanceof Element element) result.add(element);
			}
		}
		return result;
	}
}
