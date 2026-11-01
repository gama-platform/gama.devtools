/*******************************************************************************************************
 *
 * WorkspaceIntegration.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and simulation
 * platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.templates;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Element;

import gama.ui.devtools.install.InstallModel;

/**
 * Pure text transformations used to register a new plugin in the GAMA build: modules of {@code gama.parent/pom.xml},
 * plugins of {@code gama.feature.extensions/feature.xml} and features of {@code gama.p2site/category.xml}. All
 * methods return the text unchanged if the element is already present.
 */
public final class WorkspaceIntegration {

	private WorkspaceIntegration() {}

	/**
	 * Adds {@code <module>../name</module>} after the last module whose name starts with {@code afterPrefix} (or
	 * before {@code </modules>} if there is none).
	 */
	public static String addModule(final String pom, final String name, final String afterPrefix) {
		return addModulePath(pom, "../" + name, afterPrefix);
	}

	/** Adds a Maven reactor module using a path relative to the parent POM. */
	public static String addModulePath(final String pom, final String modulePath, final String afterPrefix) {
		String module = modulePath.replace('\\', '/');
		if (modules(pom).contains(module)) return pom;
		try {
			var document = InstallModel.xml(new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8)));
			Element modules = child(document.getDocumentElement(), "modules");
			Element added = document.createElementNS(modules.getNamespaceURI(), "module");
			added.setTextContent(module);
			org.w3c.dom.Node after = null;
			for (var node = modules.getFirstChild(); node != null; node = node.getNextSibling()) {
				if (node instanceof Element existing && "module".equals(tagName(existing))
						&& existing.getTextContent().strip().startsWith("../" + afterPrefix)) after = node;
			}
			if (after == null) modules.appendChild(added);
			else modules.insertBefore(added, after.getNextSibling());
			return serialize(document);
		} catch (Exception e) {
			throw new IllegalArgumentException("Cannot add a source module to the GAMA parent POM.", e);
		}
	}

	private static String serialize(final org.w3c.dom.Document document) throws Exception {
		TransformerFactory factory = TransformerFactory.newInstance();
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
		var output = new java.io.StringWriter();
		factory.newTransformer().transform(new DOMSource(document), new StreamResult(output));
		return output.toString();
	}

	/**
	 * Adds a {@code <plugin>} element at the end of a feature.
	 */
	public static String addPluginToFeature(final String feature, final String pluginId) {
		if (featurePlugins(feature).contains(pluginId)) return feature;
		int end = feature.lastIndexOf("</feature>");
		if (end < 0) throw new IllegalArgumentException("The feature has no closing </feature> element.");
		String plugin = "   <plugin\n         id=\"" + attribute(pluginId) + "\"\n         version=\"0.0.0\"/>\n\n";
		return feature.substring(0, end) + plugin + feature.substring(end);
	}

	/** Adds a feature to the product definition. */
	public static String addFeatureToProduct(final String product, final String featureId) {
		if (productFeatures(product).contains(featureId)) return product;
		int end = product.lastIndexOf("</features>");
		if (end < 0) throw new IllegalArgumentException("The GAMA product has no <features> element.");
		return product.substring(0, end) + "      <feature id=\"" + attribute(featureId) + "\" installMode=\"root\"/>\n"
				+ product.substring(end);
	}

	/** Makes an independent source build's p2 dependencies available to the GAMA product resolver. */
	public static String addP2Repositories(final String pom, final String sourcePom) {
		try {
			var target = InstallModel.xml(new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8)));
			var source = InstallModel.xml(new ByteArrayInputStream(sourcePom.getBytes(StandardCharsets.UTF_8)));
			Element sourceRepositories = child(source.getDocumentElement(), "repositories");
			if (sourceRepositories == null) return pom;
			Element root = target.getDocumentElement();
			Element repositories = child(root, "repositories");
			boolean changed = false;
			for (var node = sourceRepositories.getFirstChild(); node != null; node = node.getNextSibling()) {
				if (!(node instanceof Element repository) || !"repository".equals(tagName(repository))) continue;
				if (!"p2".equals(value(repository, "layout"))) continue;
				String id = value(repository, "id");
				String url = repositoryUrl(value(repository, "url"), child(source.getDocumentElement(), "properties"));
				if (id.isBlank() || url.contains("${") || !url.startsWith("https://")) {
					throw new IllegalArgumentException("Source p2 repositories need an id and an explicit HTTPS URL: " + url);
				}
				boolean present = false;
				if (repositories != null) {
					for (var item = repositories.getFirstChild(); item != null; item = item.getNextSibling()) {
						if (!(item instanceof Element existing) || !"repository".equals(tagName(existing))) continue;
						boolean same = value(existing, "url").replaceAll("/+$", "").equals(url.replaceAll("/+$", ""))
								&& "p2".equals(value(existing, "layout"));
						if (id.equals(value(existing, "id")) && !same) {
							throw new IllegalArgumentException("Repository id " + id + " is already used by another repository.");
						}
						present |= same;
					}
				}
				if (present) continue;
				if (repositories == null) {
					repositories = target.createElementNS(root.getNamespaceURI(), "repositories");
					root.appendChild(repositories);
				}
				Element added = target.createElementNS(root.getNamespaceURI(), "repository");
				for (String name : List.of("id", "url", "layout")) {
					Element entry = target.createElementNS(root.getNamespaceURI(), name);
					entry.setTextContent(name.equals("url") ? url : value(repository, name));
					added.appendChild(entry);
				}
				repositories.appendChild(added);
				changed = true;
			}
			if (!changed) return pom;
			return serialize(target);
		} catch (Exception e) {
			throw new IllegalArgumentException("Cannot merge source p2 repositories: " + e.getMessage(), e);
		}
	}

	private static String repositoryUrl(final String template, final Element properties) {
		String result = template;
		java.util.Set<String> visited = new java.util.HashSet<>();
		while (result.contains("${")) {
			if (properties == null || visited.size() >= 100 || !visited.add(result)) {
				throw new IllegalArgumentException("Cannot resolve source p2 repository URL: " + template);
			}
			var matcher = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(result);
			if (!matcher.find()) throw new IllegalArgumentException("Invalid source repository URL: " + template);
			String replacement = value(properties, matcher.group(1));
			if (replacement.isEmpty()) {
				throw new IllegalArgumentException("Missing source repository property: " + matcher.group(1));
			}
			result = result.substring(0, matcher.start()) + replacement + result.substring(matcher.end());
		}
		return result;
	}

	private static Element child(final Element parent, final String name) {
		for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element element && name.equals(tagName(element))) return element;
		}
		return null;
	}

	private static String value(final Element parent, final String name) {
		Element element = child(parent, name);
		return element == null ? "" : element.getTextContent().strip();
	}

	/**
	 * Adds a feature to the "gama.ext" category of the update site, after the last extension feature.
	 */
	public static String addFeatureToCategory(final String category, final String featureId) {
		if (category.contains("id=\"" + featureId + "\"")) return category;
		String entry = "   <feature url=\"features/" + featureId + "_0.0.0.jar\" id=\"" + featureId
				+ "\" version=\"0.0.0\">\n      <category name=\"gama.ext\"/>\n   </feature>\n";
		int last = category.lastIndexOf("<category name=\"gama.ext\"/>");
		if (last >= 0) {
			int close = category.indexOf("</feature>", last);
			int eol = category.indexOf('\n', close);
			return category.substring(0, eol + 1) + entry + category.substring(eol + 1);
		}
		int end = category.lastIndexOf("</site>");
		return end < 0 ? category : category.substring(0, end) + entry + category.substring(end);
	}

	private static List<String> modules(final String pom) {
		try {
			var document = InstallModel.xml(new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8)));
			List<String> result = new ArrayList<>();
			org.w3c.dom.Element modules = null;
			for (var node = document.getDocumentElement().getFirstChild(); node != null; node = node.getNextSibling()) {
				if (node instanceof org.w3c.dom.Element element && "modules".equals(tagName(element))) {
					modules = element;
					break;
				}
			}
			if (modules == null) throw new IllegalArgumentException("The GAMA parent POM has no <modules> element.");
			for (var node = modules.getFirstChild(); node != null; node = node.getNextSibling()) {
				if (node instanceof org.w3c.dom.Element element && "module".equals(tagName(element))) {
					result.add(element.getTextContent().strip());
				}
			}
			return result;
		} catch (Exception e) {
			throw new IllegalArgumentException("Cannot parse the GAMA parent POM.", e);
		}
	}

	private static List<String> featurePlugins(final String feature) {
		return ids(feature, "plugin");
	}

	private static List<String> productFeatures(final String product) {
		return ids(product, "feature");
	}

	private static List<String> ids(final String xml, final String tag) {
		try {
			var document = InstallModel.xml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
			var nodes = document.getElementsByTagName(tag);
			List<String> result = new ArrayList<>();
			for (int i = 0; i < nodes.getLength(); i++) {
				String id = ((org.w3c.dom.Element) nodes.item(i)).getAttribute("id");
				if (!id.isBlank()) result.add(id);
			}
			return result;
		} catch (Exception e) {
			throw new IllegalArgumentException("Cannot parse the XML document.", e);
		}
	}

	private static String escape(final String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String attribute(final String value) {
		return escape(value).replace("\"", "&quot;").replace("'", "&apos;");
	}

	private static String tagName(final org.w3c.dom.Element element) {
		return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
	}

}
