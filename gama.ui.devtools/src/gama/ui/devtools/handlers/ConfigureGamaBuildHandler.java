package gama.ui.devtools.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

import gama.ui.devtools.release.LocalReleaseDialog;
import gama.ui.devtools.release.ReleaseContentsDialog;
import gama.ui.devtools.util.ResourceUtils;

/** Configures the private local release build without editing the shared source tree. */
public final class ConfigureGamaBuildHandler extends AbstractHandler {

	@Override
	public Object execute(final ExecutionEvent event) {
		var shell = HandlerUtil.getActiveShell(event);
		if (!PlatformUI.getWorkbench().saveAllEditors(true)) return null;
		try {
			new ReleaseContentsDialog(shell, LocalReleaseDialog.sourceCheckout()).open();
		} catch (Exception e) {
			ResourceUtils.log(e);
			MessageDialog.openError(shell, "Configure GAMA build", e.getMessage());
		}
		return null;
	}
}
