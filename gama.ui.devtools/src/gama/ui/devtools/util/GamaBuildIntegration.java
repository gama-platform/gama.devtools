/*******************************************************************************************************
 *
 * GamaBuildIntegration.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and simulation
 * platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.w3c.dom.Element;

import gama.ui.devtools.install.InstallModel;

/** Reads the Maven, feature, and product links used by the local GAMA release build. */
public final class GamaBuildIntegration {

	public static final String PRODUCT_PROJECT = "gama.product";
	public static final String PRODUCT_FILE = "gama.product";

	public record Feature(IProject project, String id, List<String> plugins, List<String> includes) {}

	public record Analysis(String pluginId, List<Feature> containingFeatures, Set<String> productFeatures,
			boolean pluginMavenIntegrated) {}

	private GamaBuildIntegration() {}

	public static Analysis analyze(final IProject plugin) throws CoreException {
		String pluginId = bundleId(plugin);
		List<Feature> features = features();
		List<Feature> containing = features.stream().filter(f -> f.plugins().contains(pluginId)).toList();
		return new Analysis(pluginId, containing, productFeatures(), mavenIntegrated(plugin));
	}

	public static String bundleId(final IProject plugin) throws CoreException {
		try (InputStream input = Files.newInputStream(file(plugin, "META-INF/MANIFEST.MF"))) {
			String header = new Manifest(input).getMainAttributes().getValue("Bundle-SymbolicName");
			if (header == null || header.isBlank()) {
				throw new IOException("The plugin manifest has no Bundle-SymbolicName.");
			}
			String id = header.split(";", 2)[0].strip();
			if (!id.matches("[A-Za-z0-9_.-]+")) {
				throw new IOException("The plugin manifest has an invalid Bundle-SymbolicName: " + id);
			}
			return id;
		} catch (IOException e) {
			throw error("Cannot read the bundle id from " + plugin.getName() + "/META-INF/MANIFEST.MF.", e);
		}
	}

	public static List<Feature> features() throws CoreException {
		List<Feature> result = new ArrayList<>();
		Map<String, IProject> ids = new LinkedHashMap<>();
		for (IProject project : GamaProjects.root().getProjects()) {
			Path featureFile = project.getLocation() == null ? null : project.getLocation().toFile().toPath().resolve("feature.xml");
			if (featureFile == null || !Files.isRegularFile(featureFile)) continue;
			var document = read(featureFile, "Cannot read feature definition " + project.getName() + "/feature.xml.");
			Element root = document.getDocumentElement();
			if (!"feature".equals(root.getTagName())) continue;
			String id = root.getAttribute("id").strip();
			if (id.isEmpty()) throw error("The feature definition " + featureFile + " has no id.", null);
			IProject previous = ids.putIfAbsent(id, project);
			if (previous != null) {
				throw error("Workspace feature id " + id + " is declared by both " + previous.getName()
						+ " and " + project.getName() + ".", null);
			}
			result.add(new Feature(project, id, childrenIds(root, "plugin"), childrenIds(root, "includes")));
		}
		result.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
		return result;
	}

	public static Set<String> productFeatures() throws CoreException {
		Path productFile = file(GamaProjects.root().getProject(PRODUCT_PROJECT), PRODUCT_FILE);
		var product = read(productFile, "Cannot read the GAMA product definition " + productFile + ".");
		Set<String> roots = new LinkedHashSet<>();
		var entries = product.getElementsByTagName("feature");
		for (int i = 0; i < entries.getLength(); i++) {
			String id = ((Element) entries.item(i)).getAttribute("id").strip();
			if (!id.isEmpty()) roots.add(id);
		}
		Map<String, Feature> byId = new LinkedHashMap<>();
		for (Feature feature : features()) byId.put(feature.id(), feature);
		Set<String> reachable = new LinkedHashSet<>();
		List<String> queue = new ArrayList<>(roots);
		for (int i = 0; i < queue.size(); i++) {
			String id = queue.get(i);
			if (!reachable.add(id)) continue;
			Feature feature = byId.get(id);
			if (feature != null) queue.addAll(feature.includes());
		}
		return reachable;
	}

	public static boolean mavenIntegrated(final IProject project) throws CoreException {
		IProject parent = GamaProjects.root().getProject(GamaProjects.PARENT_PROJECT);
		if (!parent.exists() || parent.getLocation() == null || project.getLocation() == null) return false;
		Path pom = file(parent, "pom.xml");
		var document = read(pom, "Cannot read " + GamaProjects.PARENT_PROJECT + "/pom.xml.");
		Path location;
		try {
			location = projectPath(project);
		} catch (IOException e) {
			throw error("Cannot resolve workspace project path for " + project.getName() + ".", e);
		}
		Element modules = child(document.getDocumentElement(), "modules");
		if (modules == null) throw error(GamaProjects.PARENT_PROJECT + "/pom.xml has no <modules> element.", null);
		var entries = modules.getElementsByTagName("module");
		for (int i = 0; i < entries.getLength(); i++) {
			String module = entries.item(i).getTextContent().strip();
			if (!module.isEmpty()) {
				Path resolved = pom.getParent().resolve(module).toAbsolutePath().normalize();
				try {
					if (Files.exists(resolved)) resolved = resolved.toRealPath();
				} catch (IOException e) {
					throw error("Cannot resolve Maven module path " + module + " in " + pom + ".", e);
				}
				if (resolved.equals(location)) return hasGamaParent(project);
			}
		}
		return false;
	}

	public static String withGamaParentPath(final String pom, final IProject module) throws CoreException {
		try {
			var document = InstallModel.xml(new java.io.ByteArrayInputStream(
					pom.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			Element parent = child(document.getDocumentElement(), "parent");
			if (parent == null || !"org.gama".equals(text(parent, "groupId"))
					|| !"gama.parent".equals(text(parent, "artifactId"))) {
				throw error(module.getName()
						+ "/pom.xml must inherit from org.gama:gama.parent to be included in the GAMA reactor.", null);
			}
			Element relative = child(parent, "relativePath");
			if (relative == null) {
				relative = document.createElementNS(document.getDocumentElement().getNamespaceURI(), "relativePath");
				parent.appendChild(relative);
			}
			Path parentPath = projectPath(GamaProjects.root().getProject(GamaProjects.PARENT_PROJECT));
			String expected = relativeOrAbsolute(projectPath(module), parentPath);
			String current = relative.getTextContent().strip().replace('\\', '/').replaceAll("/+$", "");
			if (expected.equals(current)) return pom;
			relative.setTextContent(expected);
			TransformerFactory factory = TransformerFactory.newInstance();
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
			var transformer = factory.newTransformer();
			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			java.io.StringWriter output = new java.io.StringWriter();
			transformer.transform(new DOMSource(document), new StreamResult(output));
			return output.toString();
		} catch (CoreException e) {
			throw e;
		} catch (Exception e) {
			throw error("Cannot update the Maven parent path for " + module.getName() + "/pom.xml.", e);
		}
	}

	public static boolean reachable(final String featureId, final Set<String> productFeatures) {
		return productFeatures.contains(featureId);
	}

	public static String modulePath(final IProject parent, final IProject module) throws CoreException {
		try {
			return relativeOrAbsolute(projectPath(parent), projectPath(module));
		} catch (IOException e) {
			throw error("Cannot calculate a Maven module path from " + parent.getName() + " to " + module.getName() + ".",
					e);
		}
	}

	public static Path file(final IProject project, final String relativePath) throws CoreException {
		if (!project.exists() || project.getLocation() == null) {
			throw error("Import the " + project.getName() + " project into the workspace first.", null);
		}
		Path path = project.getLocation().toFile().toPath().resolve(relativePath);
		if (!Files.isRegularFile(path)) throw error("Required file is missing: " + project.getName() + "/" + relativePath, null);
		return path;
	}

	public static org.w3c.dom.Document read(final Path path, final String message) throws CoreException {
		try (InputStream input = Files.newInputStream(path)) {
			return InstallModel.xml(input);
		} catch (Exception e) {
			throw error(message, e);
		}
	}

	private static List<String> childrenIds(final Element root, final String tag) {
		List<String> result = new ArrayList<>();
		var entries = root.getElementsByTagName(tag);
		for (int i = 0; i < entries.getLength(); i++) {
			String id = ((Element) entries.item(i)).getAttribute("id").strip();
			if (!id.isEmpty()) result.add(id);
		}
		return result;
	}

	private static Element child(final Element parent, final String name) {
		for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element element
					&& name.equals(element.getLocalName() == null ? element.getTagName() : element.getLocalName())) {
				return element;
			}
		}
		return null;
	}

	private static String text(final Element parent, final String name) {
		Element element = child(parent, name);
		return element == null ? "" : element.getTextContent().strip();
	}

	private static boolean hasGamaParent(final IProject project) throws CoreException {
		var document = read(file(project, "pom.xml"), "Cannot read " + project.getName() + "/pom.xml.");
		Element parent = child(document.getDocumentElement(), "parent");
		if (parent == null || !"org.gama".equals(text(parent, "groupId"))
				|| !"gama.parent".equals(text(parent, "artifactId"))) return false;
		String relativePath = text(parent, "relativePath");
		if (relativePath.isEmpty()) {
			if (child(parent, "relativePath") != null) return false;
			relativePath = "../pom.xml";
		}
		try {
			Path resolved = projectPath(project).resolve(relativePath).normalize();
			if (Files.isDirectory(resolved)) resolved = resolved.resolve("pom.xml");
			return Files.exists(resolved) && resolved.toRealPath().equals(file(
					GamaProjects.root().getProject(GamaProjects.PARENT_PROJECT), "pom.xml").toRealPath());
		} catch (IOException e) {
			throw error("Cannot resolve the Maven parent of " + project.getName() + ".", e);
		}
	}

	private static Path projectPath(final IProject project) throws IOException {
		org.eclipse.core.runtime.IPath location = project.getLocation();
		if (location == null) location = GamaProjects.root().getLocation().append(project.getName());
		Path path = location.toFile().toPath().toAbsolutePath().normalize();
		return Files.exists(path) ? path.toRealPath() : path;
	}

	private static String relativeOrAbsolute(final Path from, final Path to) {
		try {
			return from.relativize(to).toString().replace('\\', '/');
		} catch (IllegalArgumentException e) {
			return to.toString().replace('\\', '/');
		}
	}

	private static CoreException error(final String message, final Throwable cause) {
		return new CoreException(ResourceUtils.error(message, cause));
	}
}
