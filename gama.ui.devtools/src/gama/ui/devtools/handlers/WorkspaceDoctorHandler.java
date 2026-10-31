/*******************************************************************************************************
 *
 * WorkspaceDoctorHandler.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and
 * simulation platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import gama.ui.devtools.config.ConfigurationIssue;
import gama.ui.devtools.config.PluginConfigurationChecker;
import gama.ui.devtools.install.InstallModel.Kind;
import gama.ui.devtools.launch.GamaLaunches;
import gama.ui.devtools.launch.GamaLaunches.Flavor;
import gama.ui.devtools.util.GamaProjects;
import gama.ui.devtools.util.ResourceUtils;
import gama.ui.devtools.wizards.InstallSourcesWizard;

/** Diagnoses the workspace prerequisites for developing and launching GAMA. */
public final class WorkspaceDoctorHandler extends AbstractHandler {

	private static final int MAX_DISPLAYED_ISSUES = 30;

	@Override
	public Object execute(final ExecutionEvent event) {
		var window = HandlerUtil.getActiveWorkbenchWindow(event);
		if (window == null) { return null; }
		try {
			List<IProject> projects = GamaProjects.all();
			List<ConfigurationIssue> configurationIssues =
					PluginConfigurationChecker.check(projects, new NullProgressMonitor());
			Set<String> missingProjects = GamaLaunches.missingProjects(Flavor.GUI);
			List<IVMInstall> jdks = GamaLaunches.jdks();

			List<String> actions = new ArrayList<>();
			if (configurationIssues.stream().anyMatch(issue -> issue.kind().fixable)) {
				actions.add("Repair plugin configuration");
			}
			if (!missingProjects.isEmpty()) { actions.add("Install core sources"); }
			actions.add("Close");

			String report = report(configurationIssues, missingProjects, jdks);
			MessageDialog dialog = new MessageDialog(window.getShell(), "GAMA Workspace Doctor", null, report,
					configurationIssues.isEmpty() && missingProjects.isEmpty() && !jdks.isEmpty()
							? MessageDialog.INFORMATION : MessageDialog.WARNING,
					actions.toArray(String[]::new), actions.size() - 1);
			int selection = dialog.open();
			if (selection < 0 || selection >= actions.size() - 1) { return null; }

			switch (actions.get(selection)) {
				case "Repair plugin configuration" -> {
					if (MessageDialog.openConfirm(window.getShell(), "Repair GAMA plugin configuration",
							"Repair the automatically fixable issues in open GAMA plugins?")) {
						scheduleRepair();
					}
				}
				case "Install core sources" ->
					new WizardDialog(window.getShell(), new InstallSourcesWizard(Kind.CORE)).open();
				default -> { }
			}
		} catch (CoreException e) {
			ResourceUtils.log(e);
			MessageDialog.openError(window.getShell(), "Workspace check failed", e.getStatus().getMessage());
		}
		return null;
	}

	private static String report(final List<ConfigurationIssue> issues, final Set<String> missingProjects,
			final List<IVMInstall> jdks) {
		StringBuilder text = new StringBuilder();
		if (issues.isEmpty()) {
			text.append("GAMA plugin configuration: no issues found.\n");
		} else {
			text.append("GAMA plugin configuration: ").append(issues.size()).append(" issue(s):\n");
			int displayed = Math.min(issues.size(), MAX_DISPLAYED_ISSUES);
			for (int i = 0; i < displayed; i++) {
				ConfigurationIssue issue = issues.get(i);
				text.append("  - ").append(issue.project().getName()).append(": ")
						.append(issue.message()).append('\n');
			}
			if (displayed < issues.size()) {
				text.append("  ... and ").append(issues.size() - displayed)
						.append(" more; see the Problems view for all reported issues.\n");
			}
		}

		text.append('\n');
		if (missingProjects.isEmpty()) {
			text.append("GAMA GUI launch prerequisites: available.\n");
		} else {
			text.append("GAMA GUI launch prerequisites: unavailable projects or target-platform bundles:\n");
			for (String project : missingProjects) { text.append("  - ").append(project).append('\n'); }
			text.append("Import and open the listed projects, or resolve the GAMA target platform.\n");
		}

		text.append('\n');
		if (jdks.isEmpty()) {
			text.append("JDK 25: no compatible JDK is configured in Eclipse. Add a full JDK 25 under ")
					.append("Preferences > Java > Installed JREs to run or build GAMA.\n");
		} else {
			text.append("JDK 25: ").append(jdks.size()).append(" compatible installation(s) found.\n");
		}
		return text.toString();
	}

	private static void scheduleRepair() {
		WorkspaceJob job = new WorkspaceJob("Repairing GAMA plugin configuration") {
			@Override
			public IStatus runInWorkspace(final org.eclipse.core.runtime.IProgressMonitor monitor)
					throws CoreException {
				List<IProject> projects = GamaProjects.all();
				List<ConfigurationIssue> issues = PluginConfigurationChecker.check(projects, monitor);
				PluginConfigurationChecker.repair(issues, monitor);
				List<ConfigurationIssue> remaining = PluginConfigurationChecker.check(projects, monitor);
				PluginConfigurationChecker.updateMarkers(projects, true, remaining);
				return Status.OK_STATUS;
			}
		};
		job.setRule(ResourcesPlugin.getWorkspace().getRoot());
		job.schedule();
	}
}
