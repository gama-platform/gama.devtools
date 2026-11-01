/*******************************************************************************************************
 *
 * GettingStartedStartup.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and
 * simulation platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.install;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

import gama.ui.devtools.preferences.DevToolsPreferences;
import gama.ui.devtools.wizards.GettingStartedWizard;

/** Offers the Get started wizard when Eclipse opens a workspace that contains no GAMA sources. */
public final class GettingStartedStartup implements IStartup {

	@Override
	public void earlyStartup() {
		var store = DevToolsPreferences.store();
		if (!store.getBoolean(DevToolsPreferences.GETTING_STARTED_PROMPT) || hasGamaSources()) return;
		var workbench = PlatformUI.getWorkbench();
		var display = workbench.getDisplay();
		if (display.isDisposed()) return;
		display.asyncExec(() -> {
			if (workbench.isClosing() || hasGamaSources()) return;
			var window = workbench.getActiveWorkbenchWindow();
			if (window == null && workbench.getWorkbenchWindowCount() > 0) window = workbench.getWorkbenchWindows()[0];
			if (window == null) return;
			MessageDialogWithToggle dialog = MessageDialogWithToggle.openYesNoQuestion(window.getShell(),
					"GAMA Development",
					"This workspace does not contain the GAMA sources.\n\nOpen the Get started wizard? It checks "
							+ "Eclipse and the JDK, installs the sources, builds them and runs GAMA. It is also "
							+ "available in GAMA > Get started...",
					"Do not ask again in this workspace", false, null, null);
			if (dialog.getToggleState()) store.setValue(DevToolsPreferences.GETTING_STARTED_PROMPT, false);
			if (dialog.getReturnCode() == IDialogConstants.YES_ID) GettingStartedWizard.open(window.getShell());
		});
	}

	private static boolean hasGamaSources() {
		return ResourcesPlugin.getWorkspace().getRoot().getProject("gama.core").exists();
	}
}
