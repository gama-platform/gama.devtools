/*
 * Regenerates the category.xml of a GAMA plugin p2 update site from:
 *   - the shared category list maintained in gama.devtools (gama.ui.devtools/categories.xml)
 *   - the <gama.category> property of each plugin pom.xml in the repository
 *
 * Every feature reachable from the modules of the parent pom (nested aggregator poms included) is published
 * in the categories of the plugins it contains (a feature pom can also declare its own <gama.category>).
 * Several categories can be given as a comma-separated list. A feature without any category gets the
 * fallback category.
 *
 * Normally run by the p2-categories GitHub action of gama.devtools (see README.md next to this file).
 * Manual usage (JDK 17+, no compilation needed):
 *   java GenerateCategory.java <plugin repository root>
 *
 * Environment (relative paths are resolved against the repository root):
 *   GAMA_CATEGORIES_URL     URL or local path of categories.xml
 *                           (default: DEFAULT_CATEGORIES_URL; the action passes its own local copy)
 *   GAMA_FALLBACK_CATEGORY  category of features without any <gama.category> (default: FALLBACK_CATEGORY);
 *                           set it to an empty string to fail the build instead
 *   GAMA_PARENT_POM         pom.xml listing the modules, or its folder (default: DEFAULT_PARENT_POM)
 *   GAMA_CATEGORY_OUTPUT    category.xml to write (default: DEFAULT_OUTPUT)
 */

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Manifest;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class GenerateCategory {

	static final String DEFAULT_CATEGORIES_URL =
			"https://raw.githubusercontent.com/gama-platform/gama.devtools/main/gama.ui.devtools/categories.xml";
	static final String FALLBACK_CATEGORY = "gama.category.other";
	static final String DEFAULT_PARENT_POM = "gama.plugin.parent/pom.xml";
	static final String DEFAULT_OUTPUT = "gama.plugin.p2updatesite/category.xml";
	static final String DOC_URL =
			"https://github.com/gama-platform/gama.devtools/tree/main/.github/actions/p2-categories";

	record Feature(String id, String version, List<String> plugins, List<String> includes, Set<String> categories) {}

	public static void main(String[] args) throws Exception {
		Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
		Path parentPom = root.resolve(setting("GAMA_PARENT_POM", DEFAULT_PARENT_POM)).normalize();
		if (Files.isDirectory(parentPom)) parentPom = parentPom.resolve("pom.xml");
		Path output = root.resolve(setting("GAMA_CATEGORY_OUTPUT", DEFAULT_OUTPUT)).normalize();
		// An explicitly empty value disables the fallback, so unset and blank are told apart here
		String fallback = System.getenv().getOrDefault("GAMA_FALLBACK_CATEGORY", FALLBACK_CATEGORY).trim();
		if (!Files.isDirectory(output.getParent())) fail("Output folder does not exist: " + output.getParent());

		// 1. Category definitions (id -> label), in the order of the shared file
		String source = setting("GAMA_CATEGORIES_URL", DEFAULT_CATEGORIES_URL);
		// A relative local path is read from the repository root, like the other paths
		if (!source.matches("^https?://.*")) source = root.resolve(source).normalize().toString();
		Map<String, String> categoryDefs = new LinkedHashMap<>();
		NodeList cats = parse(fetch(source)).getElementsByTagName("category");
		for (int i = 0; i < cats.getLength(); i++) {
			Element c = (Element) cats.item(i);
			categoryDefs.put(c.getAttribute("id"), c.getAttribute("name"));
		}
		if (categoryDefs.isEmpty()) fail("No <category> found in " + source);

		// 2. Walk the modules of the parent pom, descending into nested aggregator poms
		Map<String, Set<String>> pluginCategories = new HashMap<>();
		Map<String, Feature> features = new TreeMap<>();
		Deque<Path> aggregators = new ArrayDeque<>(List.of(parentPom));
		Set<Path> visitedPoms = new HashSet<>();
		while (!aggregators.isEmpty()) {
			Path aggregator = aggregators.pop();
			if (!visitedPoms.add(aggregator)) continue;
			for (String module : textList(parse(aggregator), "/project/modules/module")) {
				Path modulePath = aggregator.getParent().resolve(module).normalize();
				// A <module> can name a folder or a pom file directly
				Path pomFile = Files.isDirectory(modulePath) ? modulePath.resolve("pom.xml") : modulePath;
				Path dir = pomFile.getParent();
				Document pom = parse(pomFile);
				String packaging = text(pom, "/project/packaging");
				Set<String> declared = splitCategories(text(pom, "/project/properties/gama.category"));
				switch (packaging) {
					case "eclipse-plugin", "eclipse-test-plugin" -> pluginCategories.put(bundleId(dir, pom), declared);
					case "eclipse-feature" -> {
						Document f = parse(dir.resolve("feature.xml"));
						Element fe = f.getDocumentElement();
						features.put(fe.getAttribute("id"), new Feature(fe.getAttribute("id"),
								fe.getAttribute("version"), attrList(f, "plugin", "id"),
								attrList(f, "includes", "id"), declared));
					}
					case "pom" -> aggregators.push(pomFile);
					default -> { /* p2 update site, products... */ }
				}
			}
		}
		if (features.isEmpty()) fail("No eclipse-feature module found from " + root.relativize(parentPom));

		// 3. Resolve the categories of each feature
		Map<String, Set<String>> resolved = new TreeMap<>();
		for (Feature f : features.values()) {
			Set<String> result = new LinkedHashSet<>();
			collect(f, features, pluginCategories, result, new LinkedHashSet<>());
			if (result.isEmpty()) {
				if (fallback.isEmpty()) fail("No <gama.category> found for feature " + f.id() + " or its plugins");
				System.err.println(
						"WARNING: no <gama.category> found for feature " + f.id() + " or its plugins, using " + fallback);
				result.add(fallback);
			}
			for (String c : result) {
				if (!categoryDefs.containsKey(c)) fail("Unknown category '" + c + "' used by feature " + f.id()
						+ ". Valid ids: " + String.join(", ", categoryDefs.keySet()));
			}
			resolved.put(f.id(), result);
		}

		// 4. Write category.xml (only the categories actually used, in the shared order)
		Set<String> used = new LinkedHashSet<>();
		resolved.values().forEach(used::addAll);
		StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sb.append("<!--\n");
		sb.append("    GENERATED FILE - DO NOT EDIT. Regenerated in CI by the gama.devtools p2-categories action.\n");
		sb.append("    To change the category of a feature, set <gama.category> in the pom.xml of its plugin(s).\n");
		sb.append("    Documentation: ").append(DOC_URL).append("\n");
		sb.append("-->\n");
		sb.append("<site>\n");
		sb.append("    <description name=\"GAMA Update Site\">\n");
		sb.append("        Site containing updates to the GAMA platform software (see http://gama-platform.org)\n");
		sb.append("    </description>\n\n");
		for (Feature f : features.values()) {
			sb.append("    <feature url=\"features/").append(esc(f.id())).append('_').append(esc(f.version()))
					.append(".jar\"\n");
			sb.append("             id=\"").append(esc(f.id())).append("\"\n");
			sb.append("             version=\"").append(esc(f.version())).append("\">\n");
			for (String c : resolved.get(f.id())) sb.append("        <category name=\"").append(esc(c)).append("\"/>\n");
			sb.append("    </feature>\n");
		}
		sb.append('\n');
		for (String id : categoryDefs.keySet()) {
			if (used.contains(id)) sb.append("    <category-def name=\"").append(esc(id)).append("\" label=\"")
					.append(esc(categoryDefs.get(id))).append("\"/>\n");
		}
		sb.append("</site>\n");

		Files.writeString(output, sb.toString(), StandardCharsets.UTF_8);
		System.out.println("Generated " + root.relativize(output) + ":");
		resolved.forEach((id, c) -> System.out.println("  " + id + " -> " + String.join(", ", c)));
	}

	/** Value of an environment variable, or the default when it is unset or blank. */
	static String setting(String name, String defaultValue) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? defaultValue : value.trim();
	}

	/** Union of the feature's own categories, its plugins' and (recursively) its included features'. */
	static void collect(Feature f, Map<String, Feature> features, Map<String, Set<String>> pluginCategories,
			Set<String> result, Set<String> visited) {
		if (!visited.add(f.id())) return;
		result.addAll(f.categories());
		for (String p : f.plugins()) result.addAll(pluginCategories.getOrDefault(p, Set.of()));
		for (String inc : f.includes()) {
			Feature sub = features.get(inc);
			if (sub != null) collect(sub, features, pluginCategories, result, visited);
		}
	}

	/** Bundle-SymbolicName from the manifest (what feature.xml references), artifactId otherwise. */
	static String bundleId(Path dir, Document pom) throws Exception {
		Path mf = dir.resolve("META-INF/MANIFEST.MF");
		if (Files.exists(mf)) {
			try (InputStream in = Files.newInputStream(mf)) {
				String bsn = new Manifest(in).getMainAttributes().getValue("Bundle-SymbolicName");
				if (bsn != null) return bsn.split(";")[0].trim();
			}
		}
		return text(pom, "/project/artifactId");
	}

	static Set<String> splitCategories(String value) {
		Set<String> result = new LinkedHashSet<>();
		for (String s : value.split("[,\\s]+")) if (!s.isBlank()) result.add(s.trim());
		return result;
	}

	static byte[] fetch(String source) throws Exception {
		if (!source.matches("^https?://.*")) return Files.readAllBytes(Path.of(source));
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
				.followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create(source)).timeout(Duration.ofSeconds(30))
				.build(), HttpResponse.BodyHandlers.ofByteArray());
		if (r.statusCode() != 200) fail("HTTP " + r.statusCode() + " while downloading " + source);
		return r.body();
	}

	static Document parse(byte[] content) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.ByteArrayInputStream(content));
	}

	static Document parse(Path file) throws Exception {
		if (!Files.exists(file)) fail("Missing file " + file);
		return parse(Files.readAllBytes(file));
	}

	static String text(Document doc, String xpath) throws Exception {
		return XPathFactory.newInstance().newXPath().evaluate(xpath, doc).trim();
	}

	static List<String> textList(Document doc, String xpath) throws Exception {
		NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath().evaluate(xpath, doc, XPathConstants.NODESET);
		List<String> result = new ArrayList<>();
		for (int i = 0; i < nodes.getLength(); i++) result.add(nodes.item(i).getTextContent().trim());
		return result;
	}

	static List<String> attrList(Document doc, String tag, String attr) {
		NodeList nodes = doc.getElementsByTagName(tag);
		List<String> result = new ArrayList<>();
		for (int i = 0; i < nodes.getLength(); i++) result.add(((Element) nodes.item(i)).getAttribute(attr));
		return result;
	}

	static String esc(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	static void fail(String message) {
		System.err.println("ERROR: " + message);
		System.exit(1);
	}
}
