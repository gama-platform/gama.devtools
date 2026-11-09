package gama.ui.devtools.wizards;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

import gama.ui.devtools.install.CommunityCatalog;
import gama.ui.devtools.GamaDevToolsActivator;
import gama.ui.devtools.install.InstallModel;
import gama.ui.devtools.install.InstallModel.Kind;
import gama.ui.devtools.install.InstallModel.Project;
import gama.ui.devtools.install.InstallModel.RemoteProject;
import gama.ui.devtools.install.InstallModel.Repository;
import gama.ui.devtools.install.RepositoryCheckout;
import gama.ui.devtools.install.WorkspaceSetup;
import gama.ui.devtools.launch.GamaLaunches;
import gama.ui.devtools.util.ExplorerRefresh;
import gama.ui.devtools.util.GamaBuildIntegration;
import gama.ui.devtools.util.Notifier;
import gama.ui.devtools.util.ResourceUtils;

/** Source checkout, project selection and opt-in workspace configuration. */
public class InstallSourcesWizard extends Wizard {

	private final Kind kind;
	private SourcePage source;
	private ProjectsPage projects;
	private OptionsPage options;
	private String prepared;
	private Map<String, List<String>> definitions;
	private Map<String, String> categories = Map.of();
	private final Map<Repository, Path> checkouts = new LinkedHashMap<>();

	private final boolean scheduleBuild;

	public InstallSourcesWizard(final Kind kind) {
		this(kind, true);
	}

	/** @param scheduleBuild false when the caller refreshes, cleans and builds the workspace itself */
	public InstallSourcesWizard(final Kind kind, final boolean scheduleBuild) {
		this.kind = kind;
		this.scheduleBuild = scheduleBuild;
		setWindowTitle("Install " + kind.label);
		setNeedsProgressMonitor(true);
	}

	@Override
	public void addPages() {
		source = new SourcePage();
		projects = new ProjectsPage();
		options = new OptionsPage();
		addPage(source);
		addPage(projects);
		addPage(options);
		try (var input = WorkspaceSetup.resource(InstallModel.PROJECT_SETS)) {
			definitions = InstallModel.workingSets(input);
			var entry = Platform.getBundle(GamaDevToolsActivator.PLUGIN_ID).getEntry("categories.xml");
			if (entry == null) throw new java.io.IOException("Missing bundled categories.xml");
			try (var categoryInput = entry.openStream()) {
				categories = InstallModel.categories(categoryInput);
			}
		} catch (Exception e) {
			ResourceUtils.log(e);
			definitions = null;
			source.setErrorMessage("Cannot read bundled source installation settings: " + e.getMessage());
			source.setPageComplete(false);
		}
	}

	@FunctionalInterface
	private interface Operation {
		void run(IProgressMonitor monitor) throws Exception;
	}

	private boolean run(final WizardPage page, final Operation operation) {
		page.setErrorMessage(null);
		page.setMessage(null);
		while (true) {
			try {
				runWithProgress(operation);
				return true;
			} catch (InvocationTargetException e) {
				Throwable cause = e.getCause();
				if (cause instanceof RepositoryCheckout.LocalChangesException changes) {
					MessageDialog dialog = new MessageDialog(page.getShell(), "Local changes", null,
							"Some files in the existing GAMA installation have been modified locally. "
									+ "Before I proceed with the installation, they need to be either dismissed or stashed.",
							MessageDialog.QUESTION, new String[] { "Abort installation", "Stash them" }, 1);
					if (dialog.open() != 1) {
						page.setMessage("Installation aborted. Local changes were left untouched.", WizardPage.WARNING);
						return false;
					}
					try {
						runWithProgress(monitor -> RepositoryCheckout.stash(changes.directory(), monitor));
					} catch (InvocationTargetException stashError) {
						showError(page, stashError.getCause());
						return false;
					} catch (InterruptedException stashCancelled) {
						page.setMessage("Cancelled. Local changes were left untouched.", WizardPage.WARNING);
						return false;
					}
					continue;
				}
				showError(page, cause);
				return false;
			} catch (InterruptedException e) {
				page.setMessage("Cancelled. Completed checkouts/imports are kept.", WizardPage.WARNING);
				return false;
			}
		}
	}

	private void runWithProgress(final Operation operation)
			throws InvocationTargetException, InterruptedException {
		getContainer().run(true, true, monitor -> {
			try {
				operation.run(monitor);
			} catch (OperationCanceledException e) {
				throw new InterruptedException();
			} catch (InterruptedException e) {
				throw e;
			} catch (Exception e) {
				if (monitor.isCanceled()) throw new InterruptedException();
				throw new InvocationTargetException(e);
			} finally {
				monitor.done();
			}
		});
	}

	private static void showError(final WizardPage page, final Throwable error) {
		ResourceUtils.log(error);
		page.setErrorMessage(error.getMessage()
				+ " Completed checkouts/imports are kept; correct the problem and retry.");
	}

	@Override
	public IWizardPage getNextPage(final IWizardPage page) {
		if (page == source) {
			if (definitions == null) return null;
			Path directory = Path.of(source.location.getText().strip()).toAbsolutePath().normalize();
			List<RemoteProject> selected = source.selectedProjects();
			List<Repository> repositories = source.selectedRepositories();
			String key = repositories.stream().map(repository -> repository + "\n"
					+ source.checkout(repository, directory)).toList() + "\n" + selected;
			if (!key.equals(prepared)) {
				List<Project> available = new ArrayList<>();
				Map<Repository, Path> locations = new LinkedHashMap<>();
				if (!run(source, monitor -> {
					available.clear();
					locations.clear();
					SubMonitor sub = SubMonitor.convert(monitor, "Checking out selected source repositories", repositories.size());
					for (Repository repository : repositories) {
						if (sub.isCanceled()) throw new OperationCanceledException();
						Path checkout = source.checkout(repository, directory);
						RepositoryCheckout.prepare(repository, checkout, sub.split(1));
						List<Project> discovered = InstallModel.projects(checkout, repository, kind);
						if (discovered.isEmpty()) {
							throw new java.io.IOException("No Eclipse projects (.project files) found in " + checkout);
						}
						locations.put(repository, checkout);
						if (kind == Kind.CORE) {
							available.addAll(discovered);
						} else {
							for (RemoteProject remote : selected) {
								if (!remote.repository().equals(repository)) continue;
								Path expected = checkout.resolve(remote.path()).normalize();
								Project project = discovered.stream().filter(p -> p.directory().equals(expected))
										.findFirst().orElseThrow(() -> new java.io.IOException(
												"The selected project no longer exists: " + expected + ". Reload the GitHub project list."));
								available.add(new Project(project.name(), project.directory(), repository, true, false));
							}
						}
					}
				})) return null;
				if (available.isEmpty()) {
					source.setErrorMessage("No Eclipse projects (.project files) found in the selected checkout.");
					return null;
				}
				checkouts.clear();
				checkouts.putAll(locations);
				projects.setProjects(available);
				prepared = key;
			}
		}
		return super.getNextPage(page);
	}

	@Override
	public boolean canFinish() {
		return getContainer().getCurrentPage() == options && options.isPageComplete();
	}

	@Override
	public boolean performFinish() {
		if (!PlatformUI.getWorkbench().saveAllEditors(true)) return false;
		List<Project> selected = Arrays.stream(projects.viewer.getCheckedElements()).map(Project.class::cast).toList();
		Set<String> sets = new LinkedHashSet<>();
		if (options.sets.getSelection()) {
			for (Object item : options.workingSets.getCheckedElements()) sets.add((String) item);
		}
		boolean eclipse = options.eclipse.getSelection(), cleanup = options.cleanup.getSelection(),
				formatter = options.formatter.getSelection(), closeParent = options.closeParent.getSelection(),
				includeBuild = options.includeBuild != null && options.includeBuild.getSelection();
		Set<String> includedFeatures = new LinkedHashSet<>();
		if (!run(options, monitor -> {
			if (includeBuild) GamaLaunches.validateFeatureInclusion();
			var workspace = ResourcesPlugin.getWorkspace();
			workspace.run(progress -> {
				SubMonitor sub = SubMonitor.convert(progress, "Importing projects and configuring workspace", selected.size() + 2);
				var description = workspace.getDescription();
				boolean autoBuild = description.isAutoBuilding();
				try {
					description.setAutoBuilding(false);
					workspace.setDescription(description);
					for (Project project : selected) {
						if (sub.isCanceled()) throw new OperationCanceledException();
						WorkspaceSetup.importProject(project, sub.split(1));
					}
					if (sub.isCanceled()) throw new OperationCanceledException();
					WorkspaceSetup.preferences(eclipse, cleanup, formatter);
					sub.worked(1);
					if (includeBuild) {
						Map<Path, List<Project>> sources = new LinkedHashMap<>();
						for (Project project : projects.available) {
							if (kind == Kind.CORE && project.repository().isBase()) continue;
							if (!selected.contains(project)
									&& !ResourcesPlugin.getWorkspace().getRoot().getProject(project.name()).exists()) continue;
							Path checkout = checkouts.get(project.repository());
							sources.computeIfAbsent(checkout, key -> new ArrayList<>()).add(project);
						}
						includedFeatures.addAll(GamaBuildIntegration.includeSources(sources, sub.newChild(0)));
					}
					var parent = workspace.getRoot().getProject("gama.parent");
					if (closeParent && parent.isOpen()) parent.close(sub.split(1));
				} catch (CoreException | OperationCanceledException e) {
					throw e;
				} catch (Exception e) {
					throw new CoreException(ResourceUtils.error(e.getMessage(), e));
				} finally {
					var restore = workspace.getDescription();
					restore.setAutoBuilding(autoBuild);
					workspace.setDescription(restore);
				}
			}, workspace.getRoot(), 0, monitor);
		})) return false;
		try {
			if (!includedFeatures.isEmpty()) GamaLaunches.includeFeatures(includedFeatures);
			var workingSets = WorkspaceSetup.workingSets(definitions, sets, projects.available, kind);
			if (options.sets.getSelection() && options.explorers.getSelection() && !workingSets.isEmpty()) {
				WorkspaceSetup.organizeExplorers(workingSets);
			}
		} catch (Exception e) {
			ResourceUtils.log(e);
			options.setErrorMessage("Projects imported, but launch or working-set setup failed: " + e.getMessage());
			return false;
		}
		if (!scheduleBuild) return true;
		new WorkspaceJob("Refresh, clean and build installed GAMA workspace") {
			{
				setRule(ResourcesPlugin.getWorkspace().getRoot());
				setUser(true);
				ExplorerRefresh.after(this);
			}

			@Override
			public IStatus runInWorkspace(final IProgressMonitor monitor) throws CoreException {
				try {
					var workspace = ResourcesPlugin.getWorkspace();
					SubMonitor sub = SubMonitor.convert(monitor, getName(), 100);
					gama.ui.devtools.util.GamaProjects.removeMissingProjects(sub.split(2));
					workspace.getRoot().refreshLocal(IResource.DEPTH_INFINITE, sub.split(10));
					workspace.build(IncrementalProjectBuilder.CLEAN_BUILD, sub.split(30));
					workspace.build(IncrementalProjectBuilder.FULL_BUILD, sub.split(60));
					Notifier.show("GAMA source installation finished",
							selected.size() + " project(s) imported. Workspace refreshed and built. "
									+ "Check the Problems view for compilation errors.");
					return Status.OK_STATUS;
				} catch (OperationCanceledException e) {
					return Status.CANCEL_STATUS;
				}
			}
		}.schedule();
		return true;
	}

	private final class SourcePage extends WizardPage {
		Text branch, location, repositoryUrl, repositoryBranch, repositoryLocation;
		CheckboxTreeViewer catalog;
		Label skipped;
		final Map<String, List<Object>> groups = new LinkedHashMap<>();
		final Map<String, Path> customLocations = new LinkedHashMap<>();
		List<Object> entries = new ArrayList<>();

		SourcePage() {
			super("repositories");
			setTitle("Install " + kind.label);
			setDescription("Choose source repositories by category. Next checks out only your selections; "
					+ "nothing is installed into the running Eclipse.");
			setPageComplete(false);
		}

		@Override
		public boolean canFlipToNextPage() {
			// JFace probes this while updating buttons; only an explicit Next may check out repositories.
			return isPageComplete();
		}

		@Override
		public void createControl(final Composite parent) {
			Composite body = body(parent, 3);
			if (kind == Kind.COMMUNITY) branch = text(body, "Community branch:", InstallModel.COMMUNITY_BRANCH);
			label(body, "Checkout parent directory:");
			location = new Text(body, SWT.BORDER);
			location.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			location.setText(defaultLocation());
			Button browse = new Button(body, SWT.PUSH);
			browse.setText("Browse...");
			browse.addListener(SWT.Selection, e -> {
				DirectoryDialog dialog = new DirectoryDialog(getShell());
				dialog.setFilterPath(location.getText());
				String path = dialog.open();
				if (path != null) location.setText(path);
			});
			note(body, kind == Kind.CORE
					? "GAMA sources are selected by default. Shared models, graphical modeling and Copilot are "
							+ "offered here automatically; select the additional repositories you want."
					: "Projects from https://github.com/gama-platform-plugins, grouped by their GAMA categories. "
							+ "Projects without category metadata appear under Others.");
			if (kind == Kind.COMMUNITY) {
				Button load = check(body, "Load / reload GitHub projects", false, SWT.PUSH);
				load.addListener(SWT.Selection, e -> load());
			}
			catalog = new CheckboxTreeViewer(body, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
			GridData data = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
			data.heightHint = 240;
			data.widthHint = 650;
			catalog.getControl().setLayoutData(data);
			catalog.setContentProvider(new ITreeContentProvider() {
				@Override public Object[] getElements(final Object input) { return groups.keySet().toArray(); }
				@Override public Object[] getChildren(final Object parent) {
					return parent instanceof String id ? groups.getOrDefault(id, List.of()).toArray() : new Object[0];
				}
				@Override public Object getParent(final Object element) {
					return element instanceof String ? null : category(element);
				}
				@Override public boolean hasChildren(final Object element) {
					return element instanceof String id && groups.containsKey(id);
				}
			});
			catalog.setLabelProvider(new LabelProvider() {
				@Override public String getText(final Object element) {
					if (element instanceof String id) return categories.getOrDefault(id, id);
					if (element instanceof Repository repository) return repository.name() + " (" + repository.branch() + ")";
					return element.toString();
				}
			});
			catalog.addCheckStateListener(e -> {
				if (e.getElement() instanceof String) catalog.setSubtreeChecked(e.getElement(), e.getChecked());
				updateChecks();
				validate();
			});
			Composite buttons = body(body, 2);
			buttons.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 3, 1));
			for (boolean selected : new boolean[] { true, false }) {
				Button button = new Button(buttons, SWT.PUSH);
				button.setText(selected ? "Select all" : "Deselect all");
				button.addListener(SWT.Selection, e -> {
					catalog.setCheckedElements(selected ? entries.toArray() : new Object[0]);
					updateChecks();
					validate();
				});
			}
			repositoryUrl = text(body, "Selected repository URL:", "");
			repositoryBranch = text(body, "Selected repository branch:", "");
			repositoryLocation = text(body, "Selected checkout directory:", "");
			catalog.addSelectionChangedListener(e -> showRepository());
			Button apply = check(body, "Apply settings to the selected repository", false, SWT.PUSH);
			apply.addListener(SWT.Selection, e -> applyRepository());
			skipped = note(body, "");
			if (kind == Kind.COMMUNITY) {
				branch.addModifyListener(e -> {
					setEntries(List.of(), Set.of());
					skipped.setText("Branch changed: reload the GitHub project list.");
					validate();
				});
			} else {
				setEntries(InstallModel.CORE_REPOSITORIES, Set.of(InstallModel.CORE_REPOSITORIES.get(0)));
			}
			note(body, "Checkout happens on Next and is kept even if you cancel the wizard. "
					+ "Dirty or diverged repositories must be updated manually or installed in another directory.");
			location.addModifyListener(e -> {
				showRepository();
				validate();
			});
			setControl(body);
			validate();
		}

		private void load() {
			String ref = branch.getText().strip();
			if (ref.isEmpty()) {
				setErrorMessage("Enter a branch first.");
				return;
			}
			CommunityCatalog.Catalog[] result = new CommunityCatalog.Catalog[1];
			if (run(this, monitor -> result[0] = new CommunityCatalog().load(ref, monitor))) {
				prepared = null;
				setEntries(result[0].projects(), Set.of());
				skipped.setText(result[0].unavailable().isEmpty() ? ""
						: "Not offered: " + String.join(", ", result[0].unavailable()));
				skipped.getParent().layout(true, true);
				validate();
			}
		}

		private String defaultLocation() {
			var root = ResourcesPlugin.getWorkspace().getRoot();
			var core = root.getProject("gama.core");
			Path parent = root.getLocation().toFile().toPath().getParent().resolve("git");
			if (core.exists() && core.getLocation() != null) {
				Path gama = core.getLocation().toFile().toPath().getParent();
				parent = gama.getParent();
			}
			return parent.toString();
		}

		private Repository repository(final Object element) {
			return element instanceof Repository repository ? repository
					: element instanceof RemoteProject project ? project.repository() : null;
		}

		private String category(final Object element) {
			return element instanceof RemoteProject project ? project.category() : ((Repository) element).category();
		}

		private void setEntries(final List<?> items, final Set<?> checked) {
			entries = new ArrayList<>(items);
			groups.clear();
			for (String id : categories.keySet()) {
				for (Object item : entries) {
					if (id.equals(category(item))) groups.computeIfAbsent(id, key -> new ArrayList<>()).add(item);
				}
			}
			for (Object item : entries) {
				if (!categories.containsKey(category(item))) {
					groups.computeIfAbsent(category(item), key -> new ArrayList<>()).add(item);
				}
			}
			catalog.setInput(entries);
			catalog.setCheckedElements(checked.toArray());
			catalog.expandAll();
			updateChecks();
			showRepository();
		}

		private void updateChecks() {
			for (var group : groups.entrySet()) {
				long checked = group.getValue().stream().filter(catalog::getChecked).count();
				catalog.setChecked(group.getKey(), checked > 0);
				catalog.setGrayed(group.getKey(), checked > 0 && checked < group.getValue().size());
			}
		}

		List<RemoteProject> selectedProjects() {
			return Arrays.stream(catalog.getCheckedElements()).filter(RemoteProject.class::isInstance)
					.map(RemoteProject.class::cast).toList();
		}

		List<Repository> selectedRepositories() {
			return Arrays.stream(catalog.getCheckedElements()).map(this::repository).filter(java.util.Objects::nonNull)
					.distinct().toList();
		}

		Path checkout(final Repository repository, final Path parent) {
			if (customLocations.containsKey(repository.name())) return customLocations.get(repository.name());
			var core = ResourcesPlugin.getWorkspace().getRoot().getProject("gama.core");
			if (repository.isBase() && core.exists() && core.getLocation() != null) {
				Path existing = core.getLocation().toFile().toPath().getParent().toAbsolutePath().normalize();
				if (parent.equals(existing.getParent())) return existing;
			}
			return parent.resolve(repository.name());
		}

		private void showRepository() {
			Repository repository = repository(catalog.getStructuredSelection().getFirstElement());
			repositoryUrl.setText(repository == null ? "" : repository.url());
			repositoryBranch.setText(repository == null ? "" : repository.branch());
			if (repository == null || location.getText().isBlank()) {
				repositoryLocation.setText("");
				return;
			}
			try {
				repositoryLocation.setText(checkout(repository, Path.of(location.getText().strip())
						.toAbsolutePath().normalize()).toString());
			} catch (java.nio.file.InvalidPathException e) {
				repositoryLocation.setText("");
			}
		}

		private void applyRepository() {
			Repository old = repository(catalog.getStructuredSelection().getFirstElement());
			if (old == null) return;
			try {
				String url = repositoryUrl.getText().strip(), ref = repositoryBranch.getText().strip();
				var uri = new org.eclipse.jgit.transport.URIish(url);
				if (!"https".equals(uri.getScheme()) || uri.getHost() == null) {
					setErrorMessage("Use an HTTPS Git repository URL.");
					return;
				}
				if (!org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + ref)) {
					setErrorMessage("Enter a valid Git branch name.");
					return;
				}
				Path directory = Path.of(repositoryLocation.getText().strip()).normalize();
				if (!directory.isAbsolute()) {
					setErrorMessage("Choose an absolute checkout directory.");
					return;
				}
				Repository replacement = new Repository(old.name(), url, ref, old.category());
				Set<Object> checked = new LinkedHashSet<>();
				List<Object> updated = new ArrayList<>();
				for (Object item : entries) {
					Object next = item;
					if (old.equals(repository(item))) {
						next = item instanceof RemoteProject project
								? new RemoteProject(replacement, project.path(), project.category()) : replacement;
					}
					updated.add(next);
					if (catalog.getChecked(item)) checked.add(next);
				}
				customLocations.put(old.name(), directory);
				setEntries(updated, checked);
				prepared = null;
				validate();
			} catch (java.net.URISyntaxException | java.nio.file.InvalidPathException e) {
				setErrorMessage(e.getMessage());
			}
		}

		private void validate() {
			setErrorMessage(null);
			setPageComplete(false);
			if (definitions == null) {
				setErrorMessage("Bundled installation settings could not be loaded. See the Error Log.");
				return;
			}
			if (location.getText().isBlank()) return;
			if (branch != null && !org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + branch.getText().strip())) {
				setErrorMessage("Enter a valid Git branch name.");
				return;
			}
			try {
				Path directory = Path.of(location.getText().strip());
				if (!directory.isAbsolute()) {
					setErrorMessage("Choose an absolute checkout directory.");
					return;
				}
				if (Files.exists(directory) && !Files.isDirectory(directory)) {
					setErrorMessage("The checkout location is not a directory.");
					return;
				}
				List<Path> used = new ArrayList<>();
				for (Repository repository : selectedRepositories()) {
					Path path = checkout(repository, directory.normalize());
					if (Files.exists(path) && !Files.isDirectory(path)) {
						setErrorMessage("The checkout location is not a directory: " + path);
						return;
					}
					if (used.stream().anyMatch(other -> path.startsWith(other) || other.startsWith(path))) {
						setErrorMessage("Selected repositories need separate, non-nested checkout directories.");
						return;
					}
					used.add(path);
				}
			} catch (java.nio.file.InvalidPathException e) {
				setErrorMessage(e.getMessage());
				return;
			}
			setPageComplete(!selectedRepositories().isEmpty());
		}
	}

	private final class ProjectsPage extends WizardPage {
		CheckboxTableViewer viewer;
		List<Project> available = List.of();

		ProjectsPage() {
			super("projects");
			setTitle("Import projects");
			setDescription("Choose projects to import. Existing workspace projects cannot be selected. "
					+ "Core Maven modules and projects from the selected additional core repositories are preselected; "
					+ String.join(" and ", InstallModel.REQUIRED_CORE_PROJECTS.stream().sorted().toList())
					+ " are always imported.");
		}

		@Override
		public void createControl(final Composite parent) {
			Composite body = body(parent, 3);
			viewer = table(body);
			viewer.setLabelProvider(new LabelProvider() {
				@Override public String getText(final Object element) {
					Project project = (Project) element;
					return project.name() + "  -  " + project.repository().name()
							+ (exists(project) ? " (already in workspace)" : project.required() ? " (required)" : "");
				}
			});
			viewer.addCheckStateListener(e -> {
				Project project = (Project) e.getElement();
				if (exists(project)) viewer.setChecked(project, false);
				else if (project.required()) viewer.setChecked(project, true);
				validate();
			});
			selectButtons(body, viewer, () -> {
				for (Project project : available) {
					if (exists(project)) viewer.setChecked(project, false);
					else if (project.required()) viewer.setChecked(project, true);
				}
				validate();
			});
			note(body, "Projects remain in their Git checkout; they are not copied into the workspace. "
					+ "Selecting no new projects still lets you apply workspace settings.");
			setControl(body);
			populate();
		}

		void setProjects(final List<Project> projects) {
			available = List.copyOf(projects);
			if (viewer != null) populate();
		}

		private boolean exists(final Project project) {
			if (!ResourcesPlugin.getWorkspace().validateName(project.name(), IResource.PROJECT).isOK()) return false;
			return ResourcesPlugin.getWorkspace().getRoot().getProject(project.name()).exists();
		}

		private void populate() {
			viewer.setInput(available);
			viewer.setCheckedElements(available.stream().filter(p -> p.recommended() && !exists(p)).toArray());
			viewer.setGrayedElements(available.stream().filter(p -> exists(p) || p.required()).toArray());
			validate();
		}

		private void validate() {
			Set<String> names = new LinkedHashSet<>();
			setErrorMessage(null);
			for (Object element : viewer.getCheckedElements()) {
				Project project = (Project) element;
				if (!ResourcesPlugin.getWorkspace().validateName(project.name(), IResource.PROJECT).isOK()) {
					setErrorMessage("Invalid Eclipse project name: " + project.name());
					break;
				}
				if (!names.add(project.name())) {
					setErrorMessage("Multiple selected projects are named " + project.name() + ". Select only one.");
					break;
				}
			}
			setPageComplete(getErrorMessage() == null);
		}
	}

	private final class OptionsPage extends WizardPage {
		Button sets, explorers, eclipse, cleanup, formatter, closeParent, includeBuild;
		CheckboxTableViewer workingSets;

		OptionsPage() {
			super("workspace");
			setTitle("Configure the workspace");
			setDescription("Choose optional working sets and preferences, then finish to import, refresh, clean and build.");
		}

		@Override
		public void setVisible(final boolean visible) {
			if (visible && workingSets != null) {
				workingSets.refresh();
				boolean optional = projects.available.stream()
						.anyMatch(project -> kind == Kind.COMMUNITY || !project.repository().isBase());
				includeBuild.setEnabled(optional);
				if (!optional) includeBuild.setSelection(false);
			}
			super.setVisible(visible);
		}

		@Override
		public void createControl(final Composite parent) {
			Composite body = body(parent, 3);
			sets = check(body, "Organize projects in GAMA working sets", true, SWT.CHECK);
			workingSets = table(body);
			Map<String, List<String>> availableDefinitions = definitions == null ? Map.of() : definitions;
			workingSets.setLabelProvider(new LabelProvider() {
				@Override public String getText(final Object element) {
					String name = (String) element;
					Set<String> imports = new LinkedHashSet<>();
					for (Object item : projects.viewer.getCheckedElements()) imports.add(((Project) item).name());
					long present = InstallModel.workingSetProjects(availableDefinitions, name, projects.available, kind).stream()
							.filter(p -> ResourcesPlugin.getWorkspace().getRoot().getProject(p).exists()
									|| imports.contains(p)).count();
					return name + " (" + present + " available; missing projects are ignored)";
				}
			});
			workingSets.setInput(new ArrayList<>(availableDefinitions.keySet()));
			workingSets.setCheckedElements(availableDefinitions.keySet().stream().filter(name -> switch (kind) {
				case CORE -> !name.equals(InstallModel.COMMUNITY_SET);
				case COMMUNITY -> name.equals(InstallModel.COMMUNITY_SET);
			}).toArray());
			explorers = check(body, "Show working sets as roots in Project Explorer and Package Explorer", true, SWT.CHECK);
			sets.addListener(SWT.Selection, e -> {
				workingSets.getControl().setEnabled(sets.getSelection());
				explorers.setEnabled(sets.getSelection());
			});
			note(body, "Existing working sets and their members are preserved; available projects are added. "
					+ "Missing projects never prevent working-set creation.");
			note(body, "Apply bundled workspace preferences (overwrites matching settings, not unrelated profiles):");
			eclipse = check(body, "Eclipse Preferences for Gama.epf", false, SWT.CHECK);
			cleanup = check(body, "Cleanup rules for Gama.xml", false, SWT.CHECK);
			formatter = check(body, "Code formatter rules for Gama.xml", false, SWT.CHECK);
			note(body, "The Eclipse preference export includes Java compiler, formatter and cleanup settings. "
					+ "Project-specific settings still take precedence. No source files are formatted or cleaned up.");
			closeParent = check(body, "Turn off Maven compilation by closing gama.parent (recommended)", true, SWT.CHECK);
			includeBuild = check(body, "Include additional imported source features in GAMA launches and Maven/release builds",
					false, SWT.CHECK);
			note(body, "This applies to additional repositories, not the base GAMA checkout. It updates the GUI product, "
					+ "the mapped feature-based GUI launch and the GAMA Maven reactor. Standalone repositories keep "
					+ "their own Maven parents and modules; their p2 repositories are made available to GAMA. Resolve "
					+ "their dependencies in Eclipse's target platform separately. For a plugin without a feature, "
					+ "use its GAMA > Add to GAMA build... context menu.");
			note(body, scheduleBuild ? "Finish saves editors, imports projects, applies your choices and schedules a refresh, "
					+ "clean build and full build of the whole workspace. Resolve the GAMA target platform "
					+ "and configure the required JDK separately if needed."
					: "Finish saves editors, imports projects and applies your choices. The Get started wizard "
							+ "then cleans and builds the workspace.");
			setControl(body);
		}
	}

	private static Composite body(final Composite parent, final int columns) {
		Composite body = new Composite(parent, SWT.NONE);
		body.setLayout(new GridLayout(columns, false));
		return body;
	}

	private static void label(final Composite parent, final String text) {
		new Label(parent, SWT.NONE).setText(text);
	}

	private static Text text(final Composite parent, final String title, final String value) {
		label(parent, title);
		Text text = new Text(parent, SWT.BORDER);
		text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		text.setText(value);
		return text;
	}

	private static Label note(final Composite parent, final String text) {
		Label label = new Label(parent, SWT.WRAP);
		GridData data = new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1);
		data.widthHint = 650;
		label.setLayoutData(data);
		label.setText(text);
		return label;
	}

	private static Button check(final Composite parent, final String text, final boolean selected, final int style) {
		Button button = new Button(parent, style);
		button.setText(text);
		button.setSelection(selected);
		button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
		return button;
	}

	private static CheckboxTableViewer table(final Composite parent) {
		CheckboxTableViewer viewer = CheckboxTableViewer.newCheckList(parent, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
		GridData data = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
		data.heightHint = 200;
		data.widthHint = 650;
		viewer.getControl().setLayoutData(data);
		viewer.setContentProvider(ArrayContentProvider.getInstance());
		viewer.setLabelProvider(new LabelProvider());
		return viewer;
	}

	private static void selectButtons(final Composite parent, final CheckboxTableViewer viewer, final Runnable changed) {
		Composite buttons = body(parent, 2);
		buttons.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 3, 1));
		for (boolean selected : new boolean[] { true, false }) {
			Button button = new Button(buttons, SWT.PUSH);
			button.setText(selected ? "Select all" : "Deselect all");
			button.addListener(SWT.Selection, e -> {
				viewer.setAllChecked(selected);
				changed.run();
			});
		}
	}
}
