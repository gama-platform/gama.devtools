/*******************************************************************************************************
 *
 * AddToGamaBuildHandler.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and simulation
 * platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.handlers;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.WorkspaceModifyOperation;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import gama.ui.devtools.templates.CodeTemplates;
import gama.ui.devtools.templates.WorkspaceIntegration;
import gama.ui.devtools.util.GamaBuildIntegration;
import gama.ui.devtools.util.GamaProjects;
import gama.ui.devtools.util.ResourceUtils;

/** Adds a workspace plugin to the feature/product and Maven reactor used by local release builds. */
public final class AddToGamaBuildHandler extends AbstractHandler {

	@Override
	public Object execute(final ExecutionEvent event) {
		var shell = HandlerUtil.getActiveShell(event);
		IProject plugin = GamaProjects.firstProject(HandlerUtil.getCurrentSelection(event));
		if (!GamaProjects.isBundleProject(plugin)) {
			MessageDialog.openError(shell, "Add to GAMA build", "Select an open Eclipse bundle project.");
			return null;
		}
		try {
			GamaBuildIntegration.Analysis analysis = GamaBuildIntegration.analyze(plugin);
			if (isComplete(analysis)) {
				MessageDialog.openInformation(shell, "Add to GAMA build", "This plugin is already included in the GAMA build.");
				return null;
			}
			if (!MessageDialog.openConfirm(shell, "Add to GAMA build",
					"Add bundle \"" + analysis.pluginId()
							+ "\" to the GAMA release build?\n\nThis updates the Maven reactor in gama.parent/pom.xml "
							+ "and integrates the plugin through a feature reachable from the GUI product in "
							+ "gama.product/gama.product. Workspace projects outside the GAMA checkout are copied "
							+ "into the private release snapshot when you build it.")) {
				return null;
			}
			if (!PlatformUI.getWorkbench().saveAllEditors(true)) return null;
			analysis = GamaBuildIntegration.analyze(plugin);

			boolean gamaParent = GamaBuildIntegration.usesGamaParent(plugin);
			if (!gamaParent && analysis.containingFeatures().isEmpty()) {
				throw new IllegalArgumentException("Import the source feature containing " + analysis.pluginId()
						+ " before adding its independent reactor to the GAMA build.");
			}
			GamaBuildIntegration.Feature feature = selectFeature(shell, analysis);
			if (feature == null) return null;
			boolean create = !feature.project().exists();
			IProject parent = GamaProjects.root().getProject(GamaProjects.PARENT_PROJECT);
			String parentPom = Files.readString(GamaBuildIntegration.file(
					parent, "pom.xml"));
			String product = Files.readString(GamaBuildIntegration.file(
					GamaProjects.root().getProject(GamaBuildIntegration.PRODUCT_PROJECT),
					GamaBuildIntegration.PRODUCT_FILE));
			String updatedFeature;
			String updatedPluginPom;
			String updatedFeaturePom;
			String updatedParent;
			if (gamaParent) {
				String featureXml = create ? featureXml(feature.id(), analysis.pluginId())
						: Files.readString(GamaBuildIntegration.file(feature.project(), "feature.xml"));
				updatedFeature = WorkspaceIntegration.addPluginToFeature(featureXml, analysis.pluginId());
				String pluginPom = Files.readString(GamaBuildIntegration.file(plugin, "pom.xml"));
				updatedPluginPom = GamaBuildIntegration.withGamaParentPath(pluginPom, plugin);
				String featurePom = create ? CodeTemplates.featurePom(feature.id())
						: Files.readString(GamaBuildIntegration.file(feature.project(), "pom.xml"));
				updatedFeaturePom = GamaBuildIntegration.withGamaParentPath(featurePom, feature.project());
				String modulesWithPlugin = WorkspaceIntegration.addModulePath(parentPom,
						GamaBuildIntegration.modulePath(parent, plugin), "gama.extension.");
				updatedParent = WorkspaceIntegration.addModulePath(modulesWithPlugin,
						GamaBuildIntegration.modulePath(parent, feature.project()), "gama.feature.extension");
			} else {
				var reactor = GamaBuildIntegration.independentReactor(plugin, feature.project());
				boolean featureIntegrated = GamaBuildIntegration.mavenIntegrated(feature.project());
				if (analysis.pluginMavenIntegrated() != featureIntegrated) {
					throw new IllegalArgumentException("The bundle or feature is already registered separately. "
							+ "Reconcile the Maven modules before adding their source reactor.");
				}
				String modules = analysis.pluginMavenIntegrated() ? parentPom
						: WorkspaceIntegration.addModulePath(parentPom,
								GamaBuildIntegration.modulePath(parent, reactor), "gama.feature.");
				updatedParent = WorkspaceIntegration.addP2Repositories(modules,
						Files.readString(reactor.resolve("pom.xml")));
				updatedFeature = null;
				updatedPluginPom = null;
				updatedFeaturePom = null;
			}
			String updatedProduct = GamaBuildIntegration.reachable(feature.id(), analysis.productFeatures()) ? product
					: WorkspaceIntegration.addFeatureToProduct(product, feature.id());

			WorkspaceModifyOperation operation = new WorkspaceModifyOperation() {
				@Override protected void execute(final IProgressMonitor monitor) throws CoreException {
					open(plugin, monitor);
					IProject productProject = GamaProjects.root().getProject(GamaBuildIntegration.PRODUCT_PROJECT);
					open(parent, monitor);
					open(productProject, monitor);
					if (create) createFeatureProject(feature.project(), monitor);
					open(feature.project(), monitor);
					if (updatedPluginPom != null) ResourceUtils.write(plugin, "pom.xml", updatedPluginPom, monitor);
					ResourceUtils.write(parent, "pom.xml", updatedParent, monitor);
					if (updatedFeature != null) ResourceUtils.write(feature.project(), "feature.xml", updatedFeature, monitor);
					if (updatedFeaturePom != null) ResourceUtils.write(feature.project(), "pom.xml", updatedFeaturePom, monitor);
					ResourceUtils.write(productProject, GamaBuildIntegration.PRODUCT_FILE, updatedProduct, monitor);
				}
			};
			PlatformUI.getWorkbench().getProgressService().busyCursorWhile(operation);
			MessageDialog.openInformation(shell, "Add to GAMA build",
					analysis.pluginId() + " is integrated. Build a local release to include it.");
		} catch (Exception e) {
			ResourceUtils.log(e);
			Throwable cause = e instanceof InvocationTargetException invocation && invocation.getCause() != null
					? invocation.getCause() : e;
			MessageDialog.openError(shell, "Add to GAMA build",
					cause.getMessage() == null ? "The plugin could not be integrated." : cause.getMessage());
		}
		return null;
	}

	private static boolean isComplete(final GamaBuildIntegration.Analysis analysis) throws CoreException {
		if (!analysis.pluginMavenIntegrated()) return false;
		for (GamaBuildIntegration.Feature feature : analysis.containingFeatures()) {
			if (GamaBuildIntegration.mavenIntegrated(feature.project())
					&& GamaBuildIntegration.reachable(feature.id(), analysis.productFeatures())) return true;
		}
		return false;
	}

	private static GamaBuildIntegration.Feature selectFeature(final org.eclipse.swt.widgets.Shell shell,
			final GamaBuildIntegration.Analysis analysis) throws CoreException {
		List<GamaBuildIntegration.Feature> candidates = analysis.containingFeatures();
		if (candidates.size() == 1) return candidates.getFirst();
		if (candidates.size() > 1) {
			ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, new LabelProvider() {
				@Override public String getText(final Object element) {
					GamaBuildIntegration.Feature f = (GamaBuildIntegration.Feature) element;
					return f.id() + " (" + f.project().getName() + ")";
				}
			});
			dialog.setTitle("Choose a GAMA feature");
			dialog.setMessage("The plugin is declared in multiple features. Choose which feature to include in the product:");
			dialog.setElements(candidates.toArray());
			return dialog.open() == Window.OK
					? (GamaBuildIntegration.Feature) dialog.getFirstResult() : null;
		}

		String id = "gama.feature.extension." + GamaProjects.shortcut(analysis.pluginId());
		IProject project = GamaProjects.root().getProject(id);
		GamaBuildIntegration.Feature existing = GamaBuildIntegration.features().stream()
				.filter(f -> f.id().equals(id)).findFirst().orElse(null);
		if (existing != null) return existing;
		if (project.exists()) {
			throw new CoreException(ResourceUtils.error("A project named " + id
					+ " already exists but is not a feature project. Choose a different project name before integration.",
					null));
		}
		if (!MessageDialog.openConfirm(shell, "Create a dedicated feature",
				"No workspace feature currently contains " + analysis.pluginId()
						+ ". Create the dedicated feature " + id + " and add it to the GAMA product?")) {
			return null;
		}
		return new GamaBuildIntegration.Feature(project, id, List.of(), List.of());
	}

	private static void createFeatureProject(final IProject project, final IProgressMonitor monitor)
			throws CoreException {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IProjectDescription description = workspace.newProjectDescription(project.getName());
		description.setNatureIds(new String[] { "org.eclipse.pde.FeatureNature" });
		ICommand builder = description.newCommand();
		builder.setBuilderName("org.eclipse.pde.FeatureBuilder");
		description.setBuildSpec(new ICommand[] { builder });
		project.create(description, monitor);
		project.open(monitor);
		ResourceUtils.write(project, "feature.xml", featureXml(project.getName(), ""), monitor);
		ResourceUtils.write(project, "build.properties", CodeTemplates.featureBuildProperties(), monitor);
		ResourceUtils.write(project, "pom.xml", CodeTemplates.featurePom(project.getName()), monitor);
	}

	private static String featureXml(final String featureId, final String pluginId) {
		String plugin = pluginId.isEmpty() ? "" : "   <plugin id=\"" + pluginId + "\" version=\"0.0.0\"/>\n";
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<feature id="%s" label="%s" version="0.0.0.qualifier" provider-name="UMMISCO">
				   <description url="https://gama-platform.org">GAMA extension feature.</description>
				   <requires>
				      <import plugin="gama.core"/>
				      <import plugin="gama.dependencies"/>
				   </requires>
				%s</feature>
				""".formatted(featureId, featureId, plugin);
	}

	private static void open(final IProject project, final IProgressMonitor monitor) throws CoreException {
		if (!project.isOpen()) project.open(monitor);
	}

}
