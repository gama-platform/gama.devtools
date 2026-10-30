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
		int anchor = -1;
		for (String existing : modules(pom)) {
			if (existing.startsWith("../" + afterPrefix)) {
				int candidate = pom.indexOf("<module>" + existing + "</module>");
				if (candidate > anchor) anchor = candidate;
			}
		}
		if (anchor >= 0) {
			int eol = pom.indexOf('\n', anchor);
			if (eol < 0) throw new IllegalArgumentException("The GAMA parent POM has no line break after its module.");
			int bol = pom.lastIndexOf('\n', anchor) + 1;
			String indent = pom.substring(bol, anchor);
			return pom.substring(0, eol + 1) + indent + "<module>" + escape(module) + "</module>\n"
					+ pom.substring(eol + 1);
		}
		int end = pom.indexOf("</modules>");
		if (end < 0) throw new IllegalArgumentException("The GAMA parent POM has no <modules> element.");
		int bol = pom.lastIndexOf('\n', end) + 1;
		return pom.substring(0, bol) + "\t\t<module>" + escape(module) + "</module>\n" + pom.substring(bol);
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
