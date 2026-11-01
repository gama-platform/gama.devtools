/*******************************************************************************************************
 *
 * GettingStartedWizard.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and simulation
 * platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.wizards;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.statushandlers.StatusManager;

import gama.ui.devtools.config.ConfigurationIssue;
import gama.ui.devtools.config.PluginConfigurationChecker;
import gama.ui.devtools.handlers.LaunchGamaHandler;
import gama.ui.devtools.install.DevelopmentEnvironment;
import gama.ui.devtools.install.DevelopmentEnvironment.Check;
import gama.ui.devtools.install.DevelopmentEnvironment.DetectedJdk;
import gama.ui.devtools.install.DevelopmentEnvironment.Level;
import gama.ui.devtools.install.InstallModel.Kind;
import gama.ui.devtools.jobs.CleanAndRebuildJob;
import gama.ui.devtools.launch.GamaLaunches;
import gama.ui.devtools.launch.GamaLaunches.Flavor;
import gama.ui.devtools.preferences.DevToolsPreferences;
import gama.ui.devtools.util.GamaProjects;
import gama.ui.devtools.util.ResourceUtils;

/**
 * Guides a developer from a fresh Eclipse and workspace to a running GAMA: checks Eclipse, configures a JDK 25,
 * installs the sources, cleans and builds the workspace, then generates the launch configuration and runs GAMA.
 */
public class GettingStartedWizard extends Wizard {

	private static final String TARGET_PREFERENCES = "org.eclipse.pde.ui.TargetPlatformPreferencePage";
	private static final String JRE_PREFERENCES = "org.eclipse.jdt.debug.ui.preferences.VMPreferencePage";
	private static final int TEXT_WIDTH = 520;

	private EnvironmentPage environment;
	private JdkPage jdk;
	private SourcesPage sources;
	private BuildPage build;
	private LaunchPage launch;

	/** Opens the wizard with a size suited to its status lists. */
	public static void open(final Shell shell) {
		WizardDialog dialog = new WizardDialog(shell, new GettingStartedWizard());
		dialog.setPageSize(720, 480);
		dialog.open();
	}

	public GettingStartedWizard() {
		setWindowTitle("Get Started with GAMA Development");
		setNeedsProgressMonitor(true);
	}

	@Override
	public void addPages() {
		addPage(environment = new EnvironmentPage());
		addPage(jdk = new JdkPage());
		addPage(sources = new SourcesPage());
		addPage(build = new BuildPage());
		addPage(launch = new LaunchPage());
	}

	@Override
	public boolean canFinish() {
		return getContainer().getCurrentPage() == launch && launch.isPageComplete();
	}

	@Override
	public IWizardPage getNextPage(final IWizardPage page) {
		// Applied only on an explicit Next: JdkPage.canFlipToNextPage() does not probe this method.
		if (page == jdk && !jdk.apply()) return null;
		return super.getNextPage(page);
	}

	@Override
	public boolean performFinish() {
		try {
			ILaunchConfiguration config = launch.existing != null && !launch.regenerate.getSelection() ? launch.existing
					: GamaLaunches.create(Flavor.GUI, launch.selectedVm());
			GamaLaunches.validate(config, Flavor.GUI);
			String mode = launch.run.getSelection() ? ILaunchManager.RUN_MODE
					: launch.debug.getSelection() ? ILaunchManager.DEBUG_MODE : null;
			if (mode != null) {
				Shell owner = getShell().getParent() instanceof Shell parent ? parent : getShell();
				LaunchGamaHandler.launchAfterWorkspaceRefresh(owner, config, mode);
			}
			return true;
		} catch (CoreException e) {
			ResourceUtils.log(e);
			launch.setErrorMessage(e.getStatus().getMessage());
			return false;
		}
	}

	@FunctionalInterface
	private interface Operation {
		void run(IProgressMonitor monitor) throws Exception;
	}

	private boolean run(final WizardPage page, final Operation operation) {
		page.setErrorMessage(null);
		try {
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
			return true;
		} catch (InvocationTargetException e) {
			ResourceUtils.log(e.getCause());
			page.setErrorMessage(e.getCause().getMessage());
		} catch (InterruptedException e) {
			page.setMessage("Cancelled.", WizardPage.WARNING);
		}
		return false;
	}

	/** Base page: a scrollable list of checks, followed by page-specific controls. */
	private abstract class StatusPage extends WizardPage {
		Composite checks;
		Composite body;

		StatusPage(final String name, final String title, final String description) {
			super(name);
			setTitle(title);
			setDescription(description);
		}

		@Override
		public final void createControl(final Composite parent) {
			ScrolledComposite scroll = new ScrolledComposite(parent, SWT.V_SCROLL);
			scroll.setExpandHorizontal(true);
			scroll.setExpandVertical(true);
			body = new Composite(scroll, SWT.NONE);
			body.setLayout(new GridLayout(1, false));
			checks = new Composite(body, SWT.NONE);
			checks.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
			createContents(body);
			scroll.setContent(body);
			scroll.addListener(SWT.Resize, e -> resize(scroll));
			setControl(scroll);
			refresh();
		}

		private void resize(final ScrolledComposite scroll) {
			int width = scroll.getClientArea().width;
			scroll.setMinSize(body.computeSize(width > 0 ? width : SWT.DEFAULT, SWT.DEFAULT));
		}

		@Override
		public void setVisible(final boolean visible) {
			if (visible) refresh();
			super.setVisible(visible);
		}

		abstract void createContents(Composite parent);

		abstract List<Check> checks();

		/** Recomputes and displays the checks, then updates the page state. */
		void refresh() {
			List<Check> list;
			try {
				list = checks();
			} catch (RuntimeException e) {
				ResourceUtils.log(e);
				list = List.of(new Check("Check failed", Level.ERROR, String.valueOf(e.getMessage())));
			}
			render(checks, list);
			updateState(list);
			body.layout(true, true);
			if (getControl() instanceof ScrolledComposite scroll) resize(scroll);
		}

		void updateState(final List<Check> list) {
			setErrorMessage(null);
			if (list.stream().anyMatch(c -> c.level() == Level.ERROR)) {
				setMessage("Some requirements are not met. Follow the links to fix them, then check again.", ERROR);
			} else if (list.stream().anyMatch(c -> c.level() == Level.WARNING)) {
				setMessage("Some recommendations are not followed. You can continue, but GAMA may not build or run.",
						WARNING);
			} else {
				setMessage(null);
			}
		}

		Button button(final Composite parent, final String text, final Runnable action) {
			Button button = new Button(parent, SWT.PUSH);
			button.setText(text);
			button.addListener(SWT.Selection, e -> action.run());
			return button;
		}

		Composite row(final Composite parent, final int columns) {
			Composite row = new Composite(parent, SWT.NONE);
			GridLayout layout = new GridLayout(columns, false);
			layout.marginWidth = 0;
			row.setLayout(layout);
			row.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
			return row;
		}

		Link note(final Composite parent, final String text) {
			Link link = new Link(parent, SWT.WRAP);
			GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
			data.widthHint = TEXT_WIDTH + 150;
			link.setLayoutData(data);
			link.setText(text);
			link.addListener(SWT.Selection, e -> follow(e.text));
			return link;
		}

		void render(final Composite area, final List<Check> list) {
			for (Control child : area.getChildren()) child.dispose();
			GridLayout layout = new GridLayout(3, false);
			layout.marginWidth = 0;
			layout.verticalSpacing = 8;
			area.setLayout(layout);
			var display = area.getDisplay();
			for (Check check : list) {
				Label icon = new Label(area, SWT.NONE);
				icon.setLayoutData(new GridData(SWT.CENTER, SWT.TOP, false, false));
				icon.setText(switch (check.level()) {
					case OK -> "\u2714";
					case WARNING -> "\u26A0";
					case ERROR -> "\u2716";
				});
				icon.setForeground(display.getSystemColor(switch (check.level()) {
					case OK -> SWT.COLOR_DARK_GREEN;
					case WARNING -> SWT.COLOR_DARK_YELLOW;
					case ERROR -> SWT.COLOR_RED;
				}));
				Label title = new Label(area, SWT.NONE);
				title.setText(check.title());
				title.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
				title.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
				Link detail = new Link(area, SWT.WRAP);
				GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
				data.widthHint = TEXT_WIDTH;
				detail.setLayoutData(data);
				detail.setText(check.detail());
				detail.addListener(SWT.Selection, e -> follow(e.text));
			}
		}

		/** Opens a link: an Eclipse action, a preference page or an external web page. */
		void follow(final String href) {
			try {
				if (DevelopmentEnvironment.INSTALL_SOFTWARE.equals(href)) {
					PlatformUI.getWorkbench().getService(IHandlerService.class)
							.executeCommand("org.eclipse.equinox.p2.ui.sdk.install", null);
				} else if (href.startsWith("pref:")) {
					PreferencesUtil.createPreferenceDialogOn(getShell(), href.substring(5), null, null).open();
					refresh();
				} else {
					PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(URI.create(href).toURL());
				}
			} catch (Exception e) {
				StatusManager.getManager().handle(ResourceUtils.error("Cannot open " + href, e),
						StatusManager.LOG | StatusManager.SHOW);
			}
		}
	}

	private final class EnvironmentPage extends StatusPage {

		EnvironmentPage() {
			super("environment", "Check Eclipse",
					"GAMA is developed with Eclipse IDE for Java and DSL Developers " + DevelopmentEnvironment.RELEASE
							+ " or newer, with JDT, PDE and Xtext.");
		}

		@Override
		void createContents(final Composite parent) {
			Composite actions = row(parent, 1);
			button(actions, "Check again", this::refresh);
			note(parent, "Components installed with Help > Install New Software... are available only after "
					+ "restarting Eclipse. Reopen this wizard with GAMA > Get started... afterwards.");
			Button prompt = new Button(parent, SWT.CHECK);
			prompt.setText("Offer this wizard when this workspace contains no GAMA sources");
			IPreferenceStore store = DevToolsPreferences.store();
			prompt.setSelection(store.getBoolean(DevToolsPreferences.GETTING_STARTED_PROMPT));
			prompt.addListener(SWT.Selection,
					e -> store.setValue(DevToolsPreferences.GETTING_STARTED_PROMPT, prompt.getSelection()));
		}

		@Override
		List<Check> checks() {
			return DevelopmentEnvironment.eclipseChecks();
		}
	}

	private final class JdkPage extends StatusPage {
		ComboViewer choices;
		Button makeDefault, environments;
		List<Object> options = new ArrayList<>();

		JdkPage() {
			super("jdk", "Configure JDK 25",
					"GAMA is compiled and launched with a full JDK 25, which must be registered in Eclipse, be the "
							+ "default JRE and be associated with JavaSE-25 and JavaSE-21.");
		}

		@Override
		public boolean canFlipToNextPage() {
			return isPageComplete();
		}

		@Override
		void createContents(final Composite parent) {
			new Label(parent, SWT.NONE).setText("JDK to use for GAMA:");
			choices = new ComboViewer(parent, SWT.READ_ONLY);
			choices.getControl().setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			choices.setContentProvider(ArrayContentProvider.getInstance());
			choices.setLabelProvider(new LabelProvider() {
				@Override
				public String getText(final Object element) {
					return element instanceof IVMInstall vm ? vm.getName() + " (registered) - " + vm.getInstallLocation()
							: ((DetectedJdk) element).label() + " (will be registered)";
				}
			});
			Composite actions = row(parent, 3);
			button(actions, "Search again", this::refresh);
			button(actions, "Add a JDK folder...", this::browse);
			button(actions, "Installed JREs...", () -> follow("pref:" + JRE_PREFERENCES));
			makeDefault = new Button(parent, SWT.CHECK);
			makeDefault.setText("Make it the default JRE of the workspace");
			makeDefault.setSelection(true);
			environments = new Button(parent, SWT.CHECK);
			environments.setText("Associate it with the JavaSE-25 and JavaSE-21 execution environments");
			environments.setSelection(true);
			note(parent, "Next applies these settings. Eclipse itself keeps running on its own Java runtime. "
					+ "No JDK 25? <a href=\"" + DevelopmentEnvironment.JDK_DOWNLOAD
					+ "\">Download Eclipse Temurin 25</a>, install it, then click Search again.");
		}

		@Override
		List<Check> checks() {
			return DevelopmentEnvironment.jdkChecks();
		}

		@Override
		void refresh() {
			if (choices != null) {
				Object previous = choices.getStructuredSelection().getFirstElement();
				options = new ArrayList<>(GamaLaunches.jdks());
				options.addAll(DevelopmentEnvironment.detectJdks());
				choices.setInput(options);
				IVMInstall current = JavaRuntime.getDefaultVMInstall();
				Object selection = options.contains(previous) ? previous
						: options.contains(current) ? current : options.isEmpty() ? null : options.get(0);
				choices.setSelection(selection == null ? StructuredSelection.EMPTY : new StructuredSelection(selection));
			}
			super.refresh();
		}

		@Override
		void updateState(final List<Check> list) {
			super.updateState(list);
			if (options.isEmpty() && list.stream().anyMatch(c -> c.level() != Level.OK)) {
				setMessage("No JDK 25 found. Install one, or add its folder, to continue.", ERROR);
				setPageComplete(false);
			} else {
				setPageComplete(true);
			}
		}

		private void browse() {
			DirectoryDialog dialog = new DirectoryDialog(getShell());
			dialog.setMessage("Choose the home folder of a JDK 25");
			String path = dialog.open();
			if (path == null) return;
			DetectedJdk found = DevelopmentEnvironment.inspect(Path.of(path));
			if (found == null) {
				setErrorMessage("This folder does not contain a JDK 25 (bin/javac and a 'release' file for Java 25).");
				return;
			}
			setErrorMessage(null);
			options.add(found);
			choices.setInput(options);
			choices.setSelection(new StructuredSelection(found));
		}

		/** Registers and configures the selected JDK; false keeps the wizard on this page. */
		boolean apply() {
			Object selected = choices.getStructuredSelection().getFirstElement();
			if (selected == null) return true;
			if (selected instanceof IVMInstall vm && DevelopmentEnvironment.jdkConfigured()
					&& vm.equals(JavaRuntime.getDefaultVMInstall())) return true;
			boolean asDefault = makeDefault.getSelection(), forEnvironments = environments.getSelection();
			boolean done = run(this, monitor -> {
				monitor.beginTask("Configuring the JDK", IProgressMonitor.UNKNOWN);
				IVMInstall vm = selected instanceof IVMInstall registered ? registered
						: DevelopmentEnvironment.register((DetectedJdk) selected);
				DevelopmentEnvironment.configure(vm, asDefault, forEnvironments, monitor);
			});
			refresh();
			return done;
		}
	}

	private final class SourcesPage extends StatusPage {

		SourcesPage() {
			super("sources", "Install the GAMA sources",
					"Clone github.com/gama-platform/gama and import its projects. Optionally add the graphical "
							+ "editor and community plugins.");
		}

		@Override
		void createContents(final Composite parent) {
			Composite actions = row(parent, 5);
			button(actions, "Install core plugins...", () -> install(Kind.CORE));
			button(actions, "Install graphical editor...", () -> install(Kind.GRAPHICAL));
			button(actions, "Install Copilot integration...", () -> install(Kind.COPILOT));
			button(actions, "Install community plugins...", () -> install(Kind.COMMUNITY));
			button(actions, "Check again", this::refresh);
			note(parent, "The installers clone with Eclipse's built-in Git; a separate Git installation is not "
					+ "required. Existing checkouts are fast-forwarded only if they have no local changes. "
					+ "The workspace is cleaned and built in the next step.");
		}

		private void install(final Kind kind) {
			new WizardDialog(getShell(), new InstallSourcesWizard(kind, false)).open();
			refresh();
		}

		@Override
		List<Check> checks() {
			List<Check> list = new ArrayList<>();
			try {
				Set<String> missing = GamaLaunches.missingProjects(Flavor.GUI);
				list.add(missing.isEmpty()
						? new Check("Core plugins", Level.OK, "The projects required to run GAMA are in the workspace.")
						: new Check("Core plugins", Level.ERROR, "Missing or closed: " + abbreviate(missing)
								+ ". Click Install core plugins..."));
			} catch (CoreException e) {
				list.add(new Check("Core plugins", Level.ERROR, e.getStatus().getMessage()));
			}
			list.add(new Check("GAMA plugins in the workspace", Level.OK, GamaProjects.all().size() + " plugin(s)."));
			return list;
		}

		@Override
		void updateState(final List<Check> list) {
			super.updateState(list);
			setPageComplete(list.stream().noneMatch(c -> c.level() == Level.ERROR));
		}
	}

	private final class BuildPage extends StatusPage {
		boolean built;
		List<Check> results = List.of();

		BuildPage() {
			super("build", "Clean and build",
					"Refresh the workspace, repair the GAMA plugin settings, regenerate the GAML additions and build "
							+ "everything. This takes a few minutes the first time.");
		}

		@Override
		void createContents(final Composite parent) {
			Composite actions = row(parent, 2);
			button(actions, "Clean and build now", this::build);
			button(actions, "Check again", () -> {
				if (built) results = diagnose(results.isEmpty() ? null : results.get(0));
				refresh();
			});
			note(parent, "The build can also be started at any time with GAMA > Clean GAMA plugins (Cmd/Ctrl+B). "
					+ "Remaining problems are shown in the Problems view, many with quick fixes (Ctrl/Cmd+1).");
		}

		private void build() {
			if (!PlatformUI.getWorkbench().saveAllEditors(true)) return;
			CleanAndRebuildJob job = new CleanAndRebuildJob(GamaProjects.all(), true);
			boolean[] cancelled = { false };
			if (!run(this, monitor -> {
				IWorkspace workspace = ResourcesPlugin.getWorkspace();
				workspace.run(progress -> {
					IStatus status = job.runInWorkspace(progress);
					if (status.matches(IStatus.CANCEL)) cancelled[0] = true;
				}, workspace.getRoot(), IWorkspace.AVOID_UPDATE, monitor);
			}) || cancelled[0]) {
				if (cancelled[0]) setMessage("Build cancelled.", WARNING);
				return;
			}
			built = true;
			results = diagnose(job.succeeded()
					? new Check("Build", Level.OK, "All GAML additions were generated and no build error remains.")
					: new Check("Build", Level.WARNING, "The build finished with errors or missing GAML additions. "
							+ "See the Problems view; the checks below may explain why."));
			refresh();
		}

		private List<Check> diagnose(final Check outcome) {
			List<Check> list = new ArrayList<>();
			if (outcome != null) list.add(outcome);
			List<ConfigurationIssue> issues = PluginConfigurationChecker.check(GamaProjects.all(),
					new NullProgressMonitor());
			list.add(issues.isEmpty() ? new Check("Plugin configuration", Level.OK, "No issues.")
					: new Check("Plugin configuration", Level.WARNING, issues.size() + " issue(s), e.g. "
							+ issues.get(0).project().getName() + ": " + issues.get(0).message()
							+ ". They are listed in the Problems view with quick fixes."));
			Map<String, Set<String>> unresolved = DevelopmentEnvironment.unresolvedRequirements();
			if (unresolved.isEmpty()) {
				list.add(new Check("Target platform", Level.OK, "All dependencies of the GAMA plugins are resolved."));
			} else {
				StringBuilder text = new StringBuilder("Unavailable dependencies: ");
				int shown = 0;
				for (var entry : unresolved.entrySet()) {
					if (shown++ == 5) {
						text.append("... ");
						break;
					}
					text.append(entry.getKey()).append(" requires ").append(abbreviate(entry.getValue())).append("; ");
				}
				text.append("Install them with <a href=\"").append(DevelopmentEnvironment.INSTALL_SOFTWARE)
						.append("\">Help > Install New Software...</a>, or review the <a href=\"pref:")
						.append(TARGET_PREFERENCES).append("\">target platform</a>.");
				list.add(new Check("Target platform", Level.ERROR, text.toString()));
			}
			return list;
		}

		@Override
		List<Check> checks() {
			return built ? results
					: List.of(new Check("Build", Level.WARNING, "Not built yet. Click Clean and build now."));
		}

		@Override
		void updateState(final List<Check> list) {
			super.updateState(list);
			setPageComplete(built);
		}
	}

	private final class LaunchPage extends StatusPage {
		ILaunchConfiguration existing;
		ComboViewer vms;
		Button regenerate, run, debug, none;

		LaunchPage() {
			super("launch", "Run GAMA",
					"Create the GAMA - GUI launch configuration and start GAMA from the workspace sources.");
		}

		@Override
		void createContents(final Composite parent) {
			regenerate = new Button(parent, SWT.CHECK);
			regenerate.setText("Generate a new default configuration (the existing one is kept)");
			regenerate.addListener(SWT.Selection, e -> refresh());
			new Label(parent, SWT.NONE).setText("JDK 25 for the generated configuration:");
			vms = new ComboViewer(parent, SWT.READ_ONLY);
			vms.getControl().setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			vms.setContentProvider(ArrayContentProvider.getInstance());
			vms.setLabelProvider(new LabelProvider() {
				@Override
				public String getText(final Object element) {
					return ((IVMInstall) element).getName() + " - " + ((IVMInstall) element).getInstallLocation();
				}
			});
			vms.addSelectionChangedListener(e -> refresh());
			run = new Button(parent, SWT.RADIO);
			run.setText("Run GAMA when finishing");
			run.setSelection(true);
			debug = new Button(parent, SWT.RADIO);
			debug.setText("Debug GAMA when finishing");
			none = new Button(parent, SWT.RADIO);
			none.setText("Only create the configuration");
			note(parent, "Later, use the Run and Debug buttons of the GAMA toolbar, or GAMA > Run GAMA. "
					+ "GAMA > Run GAMA > Configure... edits the configurations.");
		}

		IVMInstall selectedVm() {
			return (IVMInstall) vms.getStructuredSelection().getFirstElement();
		}

		@Override
		List<Check> checks() {
			List<Check> list = new ArrayList<>();
			try {
				existing = GamaLaunches.mapped(Flavor.GUI);
			} catch (CoreException e) {
				existing = null;
			}
			List<IVMInstall> jdks = GamaLaunches.jdks();
			if (vms.getInput() == null || !jdks.equals(vms.getInput())) {
				vms.setInput(jdks);
				IVMInstall current = JavaRuntime.getDefaultVMInstall();
				if (!jdks.isEmpty()) vms.setSelection(new StructuredSelection(jdks.contains(current) ? current : jdks.get(0)));
			}
			boolean generate = existing == null || regenerate.getSelection();
			regenerate.setEnabled(existing != null);
			vms.getControl().setEnabled(generate);
			if (existing == null) {
				list.add(new Check("Launch configuration", Level.OK, "GAMA - GUI will be generated from gama.product."));
			} else {
				list.add(new Check("Launch configuration", Level.OK, "Uses the existing configuration \""
						+ existing.getName() + "\"" + (generate ? "; a new one will be generated." : ".")));
			}
			if (generate && selectedVm() == null) {
				list.add(new Check("JDK 25", Level.ERROR, "No JDK 25 is registered. Go back to the JDK page."));
			}
			try {
				Set<String> missing = GamaLaunches.missingProjects(Flavor.GUI);
				if (!missing.isEmpty()) list.add(new Check("Sources", Level.ERROR,
						"Missing or closed: " + abbreviate(missing) + ". Go back to the sources page."));
			} catch (CoreException e) {
				list.add(new Check("Sources", Level.ERROR, e.getStatus().getMessage()));
			}
			return list;
		}

		@Override
		void updateState(final List<Check> list) {
			super.updateState(list);
			setPageComplete(list.stream().noneMatch(c -> c.level() == Level.ERROR));
		}
	}

	private static String abbreviate(final Iterable<String> items) {
		List<String> list = new ArrayList<>();
		items.forEach(list::add);
		return list.size() <= 6 ? String.join(", ", list)
				: String.join(", ", list.subList(0, 6)) + " and " + (list.size() - 6) + " more";
	}
}
