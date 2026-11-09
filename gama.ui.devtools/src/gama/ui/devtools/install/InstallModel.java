package gama.ui.devtools.install;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Filesystem and project-set parsing, independent of Eclipse. */
public final class InstallModel {

	public static final String COMMUNITY_SET = "==================== COMMUNITY";
	public static final String MODELS_SET = "==================== MODELS";
	public static final String EXTENSIONS_SET = "==================== EXTENSIONS";
	public static final String COMMUNITY_BRANCH = "GAMA_2026-06";
	public static final String PROJECT_SETS = "Project sets for Gama.psf";
	public static final String OTHER_CATEGORY = "gama.category.other";
	public static final List<Repository> CORE_REPOSITORIES = List.of(
			new Repository("gama", "https://github.com/gama-platform/gama.git", "main", "gama.category.core"),
			new Repository("gama.shared.models", "https://github.com/gama-platform/gama.shared.models.git",
					"main", "gama.category.models"),
			new Repository("gama.graphical.modeling", "https://github.com/gama-platform-plugins/gama.graphical.modeling.git",
					COMMUNITY_BRANCH, "gama.category.display"),
			new Repository("gama.copilot", "https://github.com/gama-platform/gama.copilot.git", "main", "gama.category.ai"));
	/** Core projects that must always be imported (unless already present in the workspace). */
	public static final Set<String> REQUIRED_CORE_PROJECTS =
			Set.of("gama.processor", "gama.annotations", "gaml.grammar");

	public enum Kind {
		CORE("core plugins"),
		COMMUNITY("community plugins");

		public final String label;

		Kind(final String label) {
			this.label = label;
		}
	}

	public record Repository(String name, String url, String branch, String category) {
		public boolean isBase() {
			return name.equals("gama");
		}
	}

	public record Project(String name, Path directory, Repository repository, boolean recommended,
			boolean required) {}

	public record RemoteProject(Repository repository, String path, String category) {
		@Override
		public String toString() {
			return repository.name() + " / " + (path.isEmpty() ? "(root project)" : path);
		}
	}

	private InstallModel() {}

	public static Document xml(final InputStream input) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
		return factory.newDocumentBuilder().parse(input);
	}

	public static List<Project> projects(final Path root, final Repository repository, final Kind kind)
			throws Exception {
		Set<Path> recommended = new LinkedHashSet<>();
		Path pom = root.resolve("gama.parent/pom.xml");
		boolean base = kind == Kind.CORE && repository.isBase();
		if (base && Files.isRegularFile(pom)) {
			try (InputStream input = Files.newInputStream(pom)) {
				var modules = xml(input).getElementsByTagName("module");
				for (int i = 0; i < modules.getLength(); i++) {
					recommended.add(pom.getParent().resolve(modules.item(i).getTextContent().strip()).normalize());
				}
			}
			recommended.add(pom.getParent());
		}
		List<Project> projects = new ArrayList<>();
		List<Path> descriptions = new ArrayList<>();
		// Community repositories sometimes keep projects below a plugins/ directory.
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
				if (directory.equals(root)) return FileVisitResult.CONTINUE;
				if (Set.of(".git", ".metadata", "target", "bin").contains(directory.getFileName().toString())
						|| base && root.relativize(directory).getNameCount() > 1) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
				if (file.getFileName().toString().equals(".project")) descriptions.add(file);
				return FileVisitResult.CONTINUE;
			}
		});
		for (Path description : descriptions) {
			Path directory = description.getParent();
			try (InputStream input = Files.newInputStream(description)) {
				Element document = xml(input).getDocumentElement();
				String name = document.getElementsByTagName("name").item(0).getTextContent().strip();
				boolean required = base && REQUIRED_CORE_PROJECTS.contains(name);
				projects.add(new Project(name, directory, repository,
						required || kind == Kind.CORE && !base
								|| recommended.contains(directory.normalize()), required));
			}
		}
		projects.sort(Comparator.comparing(Project::name));
		return projects;
	}

	public static String category(final InputStream input, final String fallback) throws Exception {
		var children = xml(input).getDocumentElement().getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			if (!(children.item(i) instanceof Element properties) || !properties.getTagName().equals("properties")) continue;
			var categories = properties.getElementsByTagName("gama.category");
			if (categories.getLength() > 0 && !categories.item(0).getTextContent().isBlank()) {
				return categories.item(0).getTextContent().strip();
			}
		}
		return fallback;
	}

	public static Map<String, String> categories(final InputStream input) throws Exception {
		Map<String, String> result = new LinkedHashMap<>();
		var categories = xml(input).getElementsByTagName("category");
		for (int i = 0; i < categories.getLength(); i++) {
			Element category = (Element) categories.item(i);
			result.put(category.getAttribute("id"), category.getAttribute("name"));
		}
		return result;
	}

	public static Set<String> workingSetProjects(final Map<String, List<String>> definitions,
			final String name, final List<Project> projects, final Kind kind) {
		Set<String> result = new LinkedHashSet<>(definitions.getOrDefault(name, List.of()));
		for (Project project : projects) {
			if (kind == Kind.COMMUNITY && COMMUNITY_SET.equals(name)
					|| project.repository().name().equals("gama.copilot") && EXTENSIONS_SET.equals(name)) {
				result.add(project.name());
			}
		}
		return result;
	}

	public static Map<String, List<String>> workingSets(final InputStream input) throws Exception {
		Map<String, List<String>> result = new LinkedHashMap<>();
		var sets = xml(input).getElementsByTagName("workingSets");
		for (int i = 0; i < sets.getLength(); i++) {
			Element set = (Element) sets.item(i);
			List<String> projects = new ArrayList<>();
			var items = set.getElementsByTagName("item");
			for (int j = 0; j < items.getLength(); j++) {
				Element item = (Element) items.item(j);
				String path = item.getAttribute("path");
				String java = item.getAttribute("elementID");
				if (path.startsWith("/")) projects.add(path.substring(1).split("/")[0]);
				else if (java.startsWith("=")) projects.add(java.substring(1).split("[/<>]")[0]);
			}
			result.put(set.getAttribute("name"), projects);
		}
		result.putIfAbsent(COMMUNITY_SET, List.of());
		return result;
	}
}
