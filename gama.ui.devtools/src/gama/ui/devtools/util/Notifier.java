/*******************************************************************************************************
 *
 * Notifier.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and simulation platform
 * (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.util;

import org.eclipse.jface.notifications.NotificationPopup;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

/**
 * Non-modal notifications displayed at the end of the background operations.
 */
public final class Notifier {

	private Notifier() {}

	/**
	 * Shows a transient popup in the bottom-right corner of the workbench. Can be called from any thread.
	 */
	public static void show(final String title, final String text) {
		if (!PlatformUI.isWorkbenchRunning()) return;
		var workbench = PlatformUI.getWorkbench();
		Display display = workbench.getDisplay();
		if (display.isDisposed() || workbench.isClosing()) return;
		try {
			display.asyncExec(() -> {
				if (display.isDisposed() || workbench.isClosing()) return;
				NotificationPopup.forDisplay(display).title(title, true).text(text).delay(8000).open();
			});
		} catch (SWTException e) {
			if (e.code != SWT.ERROR_DEVICE_DISPOSED) throw e;
			ResourceUtils.log(e);
		}
	}

}
