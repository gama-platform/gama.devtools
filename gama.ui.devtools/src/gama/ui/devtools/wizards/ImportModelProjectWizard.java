package gama.ui.devtools.wizards;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.WorkspaceModifyOperation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import gama.ui.devtools.util.BuildPropertiesText;
import gama.ui.devtools.util.GamaProjects;
import gama.ui.devtools.util.ManifestText;
import gama.ui.devtools.util.ResourceUtils;

/** Copies a model project into the model library of a workspace bundle. */
public class ImportModelProjectWizard extends Wizard implements INewWizard {

	private static final List<String> CATEGORIES = List.of("Data", "Demos", "Library", "Recipes", "Tutorials", "Tests");
	private static final String GAMA_NATURE = "gama.workspace.nature";
	private static final String XTEXT_NATURE = "org.eclipse.xtext.ui.shared.xtextNature";
	private static final String XTEXT_BUILDER = "org.eclipse.xtext.ui.shared.xtextBuilder";
	private static final Set<String> OLD_NATURES = Set.of("gama.workspace.gamaNature",
			"msi.gama.application.gamaNature", "gama.workspace.builtinNature", "gama.workspace.pluginNature",
			"gama.workspace.testNature", "gama.workspace.tutorialNature", "gama.workspace.recipeNature",
			"gama.workspace.dataNature", "gama.workspace.demoNature", "msi.gama.application.builtinNature",
			"msi.gama.application.pluginNature", "msi.gama.application.testNature",
			"msi.gama.application.tutorialNature", "msi.gama.application.recipeNature");
	private IStructuredSelection selection;
	private Page page;

	@Override
	public void init(final IWorkbench workbench, final IStructuredSelection selection) {
		this.selection = selection;
		setWindowTitle("Import GAMA Project into Plugin");
		setNeedsProgressMonitor(true);
	}

	@Override
	public void addPages() {
		page = new Page();
		addPage(page);
	}

	@Override
	public boolean performFinish() {
		if (!PlatformUI.getWorkbench().saveAllEditors(true)) return false;
		Path source = Path.of(page.source.getText().strip());
		IProject plugin = page.project();
		String category = page.categories.getText();
		try {
			getContainer().run(true, false, new WorkspaceModifyOperation() {
				@Override
				protected void execute(final IProgressMonitor monitor) throws CoreException {
					importProject(source, plugin, category, monitor);
				}
			});
			return true;
		} catch (InvocationTargetException e) {
			ResourceUtils.log(e.getCause());
			page.setErrorMessage("Import failed: " + e.getCause().getMessage());
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static void importProject(final Path source, final IProject plugin, final String category,
			final IProgressMonitor monitor) throws CoreException {
		try {
			Path root = source.toRealPath();
			if (!Files.isDirectory(root) || Files.isSymbolicLink(source.resolve(".project"))) {
				throw new IOException("Choose a project directory with a regular .project file.");
			}
			Document document = readDescription(root.resolve(".project"));
			Element description = document.getDocumentElement();
			if (!"projectDescription".equals(description.getTagName())) throw new IOException("Invalid .project file.");
			if (child(description, "linkedResources") != null) {
				throw new IOException("Projects with linked resources cannot be packaged; copy those resources into the project first.");
			}
			Element nameElement = child(description, "name");
			String name = nameElement == null ? "" : nameElement.getTextContent().strip();
			IStatus validName = ResourcesPlugin.getWorkspace().validateName(name, IResource.PROJECT);
			if (!validName.isOK()) throw new IOException("Invalid project name: " + validName.getMessage());
			String manifest = ResourceUtils.read(plugin, "META-INF/MANIFEST.MF");
			List<String> ids = manifest == null ? List.of() : ManifestText.getNames(manifest, "Bundle-SymbolicName");
			if (ids.size() != 1) throw new IOException("The target plugin has no valid Bundle-SymbolicName.");
			plugin.refreshLocal(IResource.DEPTH_INFINITE, monitor);
			String build = ResourceUtils.read(plugin, "build.properties");
			if (build == null) throw new IOException("The target plugin has no build.properties.");
			normalizeDescription(document, ids.getFirst());
			byte[] projectFile = serialize(document);

			IFolder destination = plugin.getFolder("models/" + category + "/" + name);
			if (destination.getLocation() == null) throw new IOException("The target plugin must be stored locally.");
			if (destination.exists() || Files.exists(destination.getLocation().toFile().toPath())) {
				throw new IOException("Destination already exists: " + destination.getFullPath());
			}
			Path target = destination.getLocation().toFile().getCanonicalFile().toPath();
			if (target.startsWith(root)) throw new IOException("The destination cannot be inside the source project.");
			IFolder parent = ResourceUtils.createFolder(plugin, "models/" + category, monitor);
			IFolder created = parent.getFolder(name);
			created.create(false, true, monitor);
			try {
				copyProject(root, created, projectFile, monitor);
				String updated = BuildPropertiesText.addAll(build, "bin.includes", List.of("models/"));
				if (!updated.equals(build)) ResourceUtils.write(plugin, "build.properties", updated, monitor);
			} catch (CoreException | IOException e) {
				try {
					created.delete(true, monitor);
				} catch (CoreException cleanup) {
					e.addSuppressed(cleanup);
					throw new CoreException(ResourceUtils.error("Import failed and the partial copy could not be removed: "
							+ created.getFullPath(), e));
				}
				throw e;
			}
		} catch (IOException | ParserConfigurationException | SAXException | TransformerException e) {
			throw new CoreException(ResourceUtils.error("Cannot import the model project: " + e.getMessage(), e));
		}
	}

	private static Document readDescription(final Path path)
			throws IOException, ParserConfigurationException, SAXException {
		try (InputStream in = Files.newInputStream(path)) {
			return readXml(in);
		}
	}

	private static Document readXml(final InputStream in)
			throws IOException, ParserConfigurationException, SAXException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
		return factory.newDocumentBuilder().parse(in);
	}

	private static boolean declaresExtension(final IProject project) throws CoreException, IOException,
			ParserConfigurationException, SAXException {
		IFile xml = project.getFile("plugin.xml");
		if (!xml.exists()) return false;
		try (InputStream in = xml.getContents()) {
			Element root = readXml(in).getDocumentElement();
			for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
				if (node instanceof Element element && "extension".equals(element.getTagName())
						&& "gaml.extension".equals(element.getAttribute("point"))) return true;
			}
			return false;
		}
	}

	private static Element child(final Element parent, final String name) {
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element element && name.equals(element.getTagName())) return element;
		}
		return null;
	}

	private static Element ensureChild(final Element parent, final String name) {
		Element element = child(parent, name);
		if (element == null) {
			element = parent.getOwnerDocument().createElement(name);
			parent.appendChild(element);
		}
		return element;
	}

	private static void normalizeDescription(final Document document, final String bundle) {
		Element root = document.getDocumentElement();
		ensureChild(root, "comment").setTextContent(bundle);
		Element natures = ensureChild(root, "natures");
		boolean gama = false, xtext = false;
		for (Node node = natures.getFirstChild(); node != null;) {
			Node next = node.getNextSibling();
			if (node instanceof Element element && "nature".equals(element.getTagName())) {
				String id = element.getTextContent().strip();
				if (OLD_NATURES.contains(id) || GAMA_NATURE.equals(id) && gama
						|| XTEXT_NATURE.equals(id) && xtext) {
					natures.removeChild(node);
				} else if (GAMA_NATURE.equals(id)) {
					gama = true;
				} else if (XTEXT_NATURE.equals(id)) {
					xtext = true;
				}
			}
			node = next;
		}
		if (!xtext) appendText(natures, "nature", XTEXT_NATURE);
		if (!gama) appendText(natures, "nature", GAMA_NATURE);
		Element buildSpec = ensureChild(root, "buildSpec");
		boolean hasBuilder = false;
		for (Node node = buildSpec.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element element && "buildCommand".equals(element.getTagName())) {
				Element name = child(element, "name");
				if (name != null && XTEXT_BUILDER.equals(name.getTextContent().strip())) hasBuilder = true;
			}
		}
		if (!hasBuilder) {
			Element command = document.createElement("buildCommand");
			appendText(command, "name", XTEXT_BUILDER);
			command.appendChild(document.createElement("arguments"));
			buildSpec.appendChild(command);
		}
	}

	private static void appendText(final Element parent, final String tag, final String value) {
		Element element = parent.getOwnerDocument().createElement(tag);
		element.setTextContent(value);
		parent.appendChild(element);
	}

	private static byte[] serialize(final Document document) throws TransformerException {
		TransformerFactory factory = TransformerFactory.newInstance();
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		factory.newTransformer().transform(new DOMSource(document), new StreamResult(out));
		return out.toByteArray();
	}

	private static void copyProject(final Path root, final IFolder destination, final byte[] projectFile,
			final IProgressMonitor monitor) throws IOException, CoreException {
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
					if (!dir.equals(root) && ".git".equals(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
					if (!dir.equals(root)) {
						try {
							ResourceUtils.createFolder(destination, root.relativize(dir).toString().replace('\\', '/'), monitor);
						} catch (CoreException e) { throw new CopyException(e); }
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
					if (attrs.isSymbolicLink() || !attrs.isRegularFile()) throw new IOException("Unsupported linked or special file: " + file);
					String relative = root.relativize(file).toString().replace('\\', '/');
					if (".git".equals(relative)) return FileVisitResult.CONTINUE;
					IFile target = destination.getFile(org.eclipse.core.runtime.IPath.fromPortableString(relative));
					try (InputStream in = ".project".equals(relative)
							? new ByteArrayInputStream(projectFile) : Files.newInputStream(file)) {
						target.create(in, false, monitor);
					} catch (CoreException e) { throw new CopyException(e); }
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (CopyException e) {
			throw e.failure;
		}
	}

	private static final class CopyException extends IOException {
		private static final long serialVersionUID = 1L;
		final CoreException failure;
		CopyException(final CoreException failure) { this.failure = failure; }
	}

	private class Page extends WizardPage {
		private Text source;
		private Combo projects, categories;
		private List<IProject> candidates;

		Page() {
			super("importModel");
			setTitle("Import a GAMA project into a plugin");
			setDescription("Copy a project directory into a plugin's model library; the original is not modified.");
		}

		IProject project() {
			int index = projects.getSelectionIndex();
			return index < 0 ? null : candidates.get(index);
		}

		@Override
		public void createControl(final Composite parent) {
			Composite c = new Composite(parent, SWT.NONE);
			c.setLayout(new GridLayout(3, false));
			new Label(c, SWT.NONE).setText("Project directory:");
			source = new Text(c, SWT.BORDER);
			source.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			Button browse = new Button(c, SWT.PUSH);
			browse.setText("Browse...");
			browse.addListener(SWT.Selection, e -> {
				DirectoryDialog dialog = new DirectoryDialog(getShell());
				dialog.setText("Choose a GAMA project directory");
				String selected = dialog.open();
				if (selected != null) source.setText(selected);
			});
			new Label(c, SWT.NONE).setText("Target plugin:");
			projects = new Combo(c, SWT.READ_ONLY);
			projects.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
			candidates = new ArrayList<>();
			for (IProject project : GamaProjects.root().getProjects()) {
				if (!project.isOpen()) continue;
				try {
					String manifest = ResourceUtils.read(project, "META-INF/MANIFEST.MF");
					List<String> ids = manifest == null ? List.of() : ManifestText.getNames(manifest, "Bundle-SymbolicName");
					if (ids.size() != 1) continue;
					String id = ids.getFirst();
					if ("gama.library".equals(id) || "gama.core".equals(id)
							|| !"gama.api".equals(id) && declaresExtension(project)) candidates.add(project);
				} catch (CoreException | IOException | ParserConfigurationException | SAXException e) {
					ResourceUtils.log(e);
				}
			}
			candidates.sort((a, b) -> a.getName().compareTo(b.getName()));
			candidates.forEach(p -> projects.add(p.getName()));
			int selected = candidates.indexOf(GamaProjects.firstProject(selection));
			if (selected >= 0) projects.select(selected);
			new Label(c, SWT.NONE).setText("Category:");
			categories = new Combo(c, SWT.READ_ONLY);
			categories.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
			CATEGORIES.forEach(categories::add);
			categories.select(CATEGORIES.indexOf("Library"));
			Label hint = new Label(c, SWT.WRAP);
			hint.setText("The copy includes project resources and excludes Git metadata. Linked resources and symbolic links must be copied into the project first.");
			hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
			source.addListener(SWT.Modify, e -> validate());
			projects.addListener(SWT.Selection, e -> validate());
			setControl(c);
			validate();
		}

		private void validate() {
			String error = null;
			if (source.getText().isBlank()) error = "Choose a project directory.";
			else {
				try {
					if (!Files.isRegularFile(Path.of(source.getText().strip()).resolve(".project"))) {
						error = "The directory must contain a .project file.";
					} else if (project() == null) {
						error = "Choose an open GAMA plugin.";
					}
				} catch (InvalidPathException e) {
					error = "Invalid project directory.";
				}
			}
			setErrorMessage(error);
			setPageComplete(error == null);
		}
	}
}
