package gama.ui.devtools.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import gama.ui.devtools.install.InstallModel;

/** Feature and plugin overrides applied only to a private release snapshot. */
public final class ReleaseContents {

	public record Feature(String id, Path file, List<String> plugins, List<String> includes) {}

	public record Selection(Set<String> addedFeatures, Set<String> excludedFeatures, Set<String> excludedPlugins) {
		public static final Selection DEFAULT = new Selection(Set.of(), Set.of(), Set.of());

		public Selection {
			addedFeatures = Set.copyOf(addedFeatures);
			excludedFeatures = Set.copyOf(excludedFeatures);
			excludedPlugins = Set.copyOf(excludedPlugins);
		}

		public String encode() {
			List<String> lines = new ArrayList<>();
			addedFeatures.stream().sorted().forEach(id -> lines.add("+ " + id));
			excludedFeatures.stream().sorted().forEach(id -> lines.add("- " + id));
			excludedPlugins.stream().sorted().forEach(id -> lines.add("p " + id));
			return String.join("\n", lines);
		}

		public static Selection decode(final String value) throws IOException {
			Set<String> added = new LinkedHashSet<>(), excluded = new LinkedHashSet<>(), plugins = new LinkedHashSet<>();
			for (String line : value.lines().toList()) {
				if (line.isBlank()) continue;
				if (!line.matches("[+\\-p] [A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?")) {
					throw new IOException("Invalid saved local build configuration. Restore the product defaults.");
				}
				String id = line.substring(2);
				if (line.startsWith("p ") != id.contains("/")) {
					throw new IOException("Invalid saved local build entry: " + line);
				}
				switch (line.charAt(0)) {
					case '+' -> added.add(id);
					case '-' -> excluded.add(id);
					case 'p' -> plugins.add(id);
					default -> throw new IOException("Invalid saved local build entry: " + line);
				}
			}
			return new Selection(added, excluded, plugins);
		}
	}

	private final Map<String, Feature> features;
	private final Set<String> roots;

	private ReleaseContents(final Map<String, Feature> features, final Set<String> roots) {
		this.features = features;
		this.roots = roots;
	}

	public List<Feature> features() {
		return features.values().stream().sorted((a, b) -> a.id().compareToIgnoreCase(b.id())).toList();
	}

	/** Reads the actual reactor, including independent and external source reactors. */
	public static ReleaseContents read(final Path source) throws Exception {
		Map<String, Feature> features = new LinkedHashMap<>();
		scan(source.resolve("gama.parent"), features, new LinkedHashSet<>());
		Document product = xml(source.resolve("gama.product/gama.product"));
		if (!"true".equals(product.getDocumentElement().getAttribute("useFeatures"))) {
			throw new IOException("The GAMA product must be feature-based to configure release contents.");
		}
		Element entries = child(product.getDocumentElement(), "features");
		if (entries == null) throw new IOException("The GAMA product has no <features> element.");
		Set<String> roots = ids(entries, "feature");
		Set<String> referenced = new LinkedHashSet<>(roots);
		for (Feature feature : List.copyOf(features.values())) referenced.addAll(feature.includes());
		for (String id : referenced) features.putIfAbsent(id, new Feature(id, null, List.of(), List.of()));
		return new ReleaseContents(features, roots);
	}

	public Set<String> included(final Selection selection) {
		Set<String> initial = new LinkedHashSet<>(roots);
		initial.addAll(selection.addedFeatures());
		return closure(initial, selection.excludedFeatures());
	}

	public Set<String> closure(final Set<String> initial, final Set<String> excluded) {
		Set<String> result = new LinkedHashSet<>();
		List<String> queue = new ArrayList<>(initial);
		for (int i = 0; i < queue.size(); i++) {
			String id = queue.get(i);
			if (excluded.contains(id) || !result.add(id)) continue;
			Feature feature = features.get(id);
			if (feature != null) queue.addAll(feature.includes());
		}
		return result;
	}

	public Selection selection(final Set<String> included, final Set<String> excludedPlugins) {
		Set<String> excluded = new LinkedHashSet<>(included(Selection.DEFAULT));
		excluded.addAll(closure(included, Set.of()));
		excluded.removeAll(included);
		Set<String> added = new LinkedHashSet<>(included);
		added.removeAll(closure(roots, excluded));
		return new Selection(added, excluded, excludedPlugins);
	}

	public void validate(final Selection selection) throws IOException {
		if (!java.util.Collections.disjoint(selection.addedFeatures(), selection.excludedFeatures())) {
			throw new IOException("A feature cannot be both included and excluded. Restore the product defaults.");
		}
		Set<String> configured = new LinkedHashSet<>(selection.addedFeatures());
		configured.addAll(selection.excludedFeatures());
		configured.removeAll(features.keySet());
		if (!configured.isEmpty()) throw new IOException("Configured features are no longer in this reactor: "
				+ String.join(", ", configured) + ". Open Configure GAMA build and restore the product defaults.");
		for (String id : selection.excludedPlugins()) {
			String[] pair = id.split("/", -1);
			Feature feature = pair.length == 2 ? features.get(pair[0]) : null;
			if (feature == null || !feature.plugins().contains(pair[1])) {
				throw new IOException("Configured plugin entry is no longer available: " + id
						+ ". Open Configure GAMA build and restore the product defaults.");
			}
		}
		if (included(selection).isEmpty()) throw new IOException("Include at least one feature in the local release.");
	}

	/** The caller must pass the staged snapshot, never the source checkout. */
	public static void apply(final Path snapshot, final Selection selection) throws Exception {
		if (selection.equals(Selection.DEFAULT)) return;
		ReleaseContents contents = read(snapshot);
		contents.validate(selection);
		Path productPath = snapshot.resolve("gama.product/gama.product");
		Path boundary = snapshot.toRealPath();
		requireSnapshotFile(productPath, boundary);
		for (Feature feature : contents.features()) {
			if (feature.file() != null) requireSnapshotFile(feature.file(), boundary);
		}
		Document product = xml(productPath);
		Element entries = child(product.getDocumentElement(), "features");
		boolean changed = remove(entries, "feature", selection.excludedFeatures());
		Set<String> present = ids(entries, "feature");
		for (String id : selection.addedFeatures()) {
			if (!present.add(id)) continue;
			Element added = product.createElement("feature");
			added.setAttribute("id", id);
			added.setAttribute("installMode", "root");
			entries.appendChild(added);
			changed = true;
		}
		if (changed) write(product, productPath);
		for (Feature feature : contents.features()) {
			if (feature.file() == null) continue;
			Document document = xml(feature.file());
			Element root = document.getDocumentElement();
			boolean edited = remove(root, "includes", selection.excludedFeatures());
			Set<String> excluded = new LinkedHashSet<>();
			for (String id : feature.plugins()) {
				if (selection.excludedPlugins().contains(feature.id() + "/" + id)) excluded.add(id);
			}
			edited |= remove(root, "plugin", excluded);
			if (edited) write(document, feature.file());
		}
	}

	private static void requireSnapshotFile(final Path file, final Path snapshot) throws IOException {
		if (!file.toRealPath().startsWith(snapshot)) {
			throw new IOException("Release contents cannot be changed outside the private snapshot: " + file);
		}
	}

	private static void scan(final Path directory, final Map<String, Feature> features, final Set<Path> visited)
			throws Exception {
		Path real = directory.toRealPath();
		if (!visited.add(real)) return;
		Document pom = xml(real.resolve("pom.xml"));
		Path definition = real.resolve("feature.xml");
		if (Files.isRegularFile(definition)) {
			Element root = xml(definition).getDocumentElement();
			String id = root.getAttribute("id").strip();
			if (!"feature".equals(root.getTagName()) || !id.matches("[A-Za-z0-9_.-]+")) {
				throw new IOException("Invalid feature definition: " + definition);
			}
			if (features.putIfAbsent(id, new Feature(id, definition, List.copyOf(ids(root, "plugin")),
					List.copyOf(ids(root, "includes")))) != null) {
				throw new IOException("Duplicate reactor feature id: " + id);
			}
		}
		Element modules = child(pom.getDocumentElement(), "modules");
		if (modules == null) return;
		for (Element module : children(modules, "module")) {
			String path = module.getTextContent().strip();
			if (path.isEmpty() || path.contains("${")) throw new IOException("Unsupported Maven module path in " + real);
			scan(real.resolve(path), features, visited);
		}
	}

	private static boolean remove(final Element parent, final String tag, final Set<String> excluded) {
		boolean changed = false;
		for (Element entry : children(parent, tag)) {
			if (excluded.contains(entry.getAttribute("id"))) {
				parent.removeChild(entry);
				changed = true;
			}
		}
		return changed;
	}

	private static Set<String> ids(final Element parent, final String tag) {
		Set<String> result = new LinkedHashSet<>();
		for (Element entry : children(parent, tag)) {
			String id = entry.getAttribute("id").strip();
			if (!id.isEmpty()) result.add(id);
		}
		return result;
	}

	private static Element child(final Element parent, final String tag) {
		return children(parent, tag).stream().findFirst().orElse(null);
	}

	private static List<Element> children(final Element parent, final String tag) {
		List<Element> result = new ArrayList<>();
		for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element element && tag.equals(element.getTagName())) result.add(element);
		}
		return result;
	}

	private static Document xml(final Path file) throws Exception {
		try (var input = Files.newInputStream(file)) {
			return InstallModel.xml(input);
		}
	}

	private static void write(final Document document, final Path file) throws Exception {
		TransformerFactory factory = TransformerFactory.newInstance();
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
		try (var output = Files.newOutputStream(file)) {
			factory.newTransformer().transform(new DOMSource(document), new StreamResult(output));
		}
	}
}
