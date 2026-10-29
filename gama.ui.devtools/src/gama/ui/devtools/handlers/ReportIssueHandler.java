package gama.ui.devtools.handlers;

import java.net.MalformedURLException;
import java.net.URI;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.statushandlers.StatusManager;

import gama.ui.devtools.util.ResourceUtils;

public final class ReportIssueHandler extends AbstractHandler {

	private static final URI NEW_ISSUE = URI.create("https://github.com/gama-platform/gama.devtools/issues/new");

	@Override
	public Object execute(ExecutionEvent event) {
		try {
			PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(NEW_ISSUE.toURL());
		} catch (MalformedURLException | PartInitException e) {
			StatusManager.getManager().handle(
					ResourceUtils.error("Cannot open the issue reporting page. Open " + NEW_ISSUE
							+ " in your web browser.", e),
					StatusManager.LOG | StatusManager.SHOW);
		}
		return null;
	}
}
