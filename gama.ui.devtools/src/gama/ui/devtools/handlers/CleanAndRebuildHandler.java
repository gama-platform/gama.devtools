/*******************************************************************************************************
 *
 * CleanAndRebuildHandler.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and simulation
 * platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.handlers;

import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

import gama.ui.devtools.jobs.CleanAndRebuildJob;
import gama.ui.devtools.preferences.DevToolsPreferences;
import gama.ui.devtools.util.GamaProjects;
import gama.ui.devtools.util.Notifier;

/**
 * Resets annotation processing and rebuilds the GAMA plugins. With the parameter
 * {@value #SCOPE} set to {@code selection}, only the GAMA plugins of the current selection are reset and rebuilt;
 * otherwise, all of them are reset and the whole workspace is rebuilt.
 */
public class CleanAndRebuildHandler extends AbstractHandler {

	/** The id of the command parameter. */
	public static final String SCOPE = "gama.ui.devtools.scope";

	@Override
	public Object execute(final ExecutionEvent event) {
		boolean selection = "selection".equals(event.getParameter(SCOPE));
		List<IProject> targets = selection ? GamaProjects.fromSelection(HandlerUtil.getCurrentSelection(event))
				: GamaProjects.all();
		if (selection && targets.isEmpty()) {
			Notifier.show("GAMA", selection ? "No GAMA plugin selected" : "No GAMA plugin in the workspace");
			return null;
		}
		var window = HandlerUtil.getActiveWorkbenchWindow(event);
		Shell shell = window == null ? null : window.getShell();
		Boolean rebuildProcessor = selection ? Boolean.FALSE : shouldRebuildProcessor(shell);
		if (rebuildProcessor == null) return null;
		PlatformUI.getWorkbench().saveAllEditors(false);
		CleanAndRebuildJob job = new CleanAndRebuildJob(targets, !selection, rebuildProcessor, false);
		job.setUser(true);
		PlatformUI.getWorkbench().getProgressService().showInDialog(shell, job);
		job.schedule();
		return null;
	}

	/**
	 * Returns whether the annotation processor should be rebuilt before cleaning all GAMA plugins, or {@code null} if
	 * the user cancelled the dialog.
	 */
	public static Boolean shouldRebuildProcessor(final Shell shell) {
		boolean rebuild = DevToolsPreferences.is(DevToolsPreferences.REBUILD_PROCESSOR_ON_CLEAN);
		if (!DevToolsPreferences.is(DevToolsPreferences.ASK_REBUILD_PROCESSOR)) return rebuild;
		MessageDialogWithToggle dialog = new MessageDialogWithToggle(shell, "Clean GAMA plugins", null,
				"Do you also want to rebuild the annotation processor (gama.processor.jar) before cleaning and building GAMA?",
				MessageDialog.QUESTION,
				new String[] { IDialogConstants.YES_LABEL, IDialogConstants.NO_LABEL, IDialogConstants.CANCEL_LABEL },
				rebuild ? 0 : 1, "Remember my choice and do not ask again", false);
		int code = dialog.open();
		if (code != IDialogConstants.YES_ID && code != IDialogConstants.NO_ID) return null;
		boolean chosen = code == IDialogConstants.YES_ID;
		DevToolsPreferences.store().setValue(DevToolsPreferences.REBUILD_PROCESSOR_ON_CLEAN, chosen);
		if (dialog.getToggleState()) {
			DevToolsPreferences.store().setValue(DevToolsPreferences.ASK_REBUILD_PROCESSOR, false);
		}
		return chosen;
	}

}
