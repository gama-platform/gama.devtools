/*******************************************************************************************************
 *
 * ExplorerRefresh.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and simulation
 * platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.util;

import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jdt.internal.ui.packageview.PackageExplorerPart;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;

/** Refreshes already-open explorers on the UI thread after a workspace operation. */
@SuppressWarnings("restriction")
public final class ExplorerRefresh {

	private ExplorerRefresh() {}

	public static void after(final Job job) {
		job.addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(final IJobChangeEvent event) {
				if (!PlatformUI.isWorkbenchRunning()) return;
				var workbench = PlatformUI.getWorkbench();
				var display = workbench.getDisplay();
				if (display.isDisposed()) return;
				// Never wait for the UI thread while holding a workspace scheduling rule.
				display.asyncExec(() -> {
					if (workbench.isClosing()) return;
					for (var window : workbench.getWorkbenchWindows()) {
						for (var page : window.getPages()) {
							for (var reference : page.getViewReferences()) {
								var part = reference.getView(false);
								TreeViewer viewer;
								if (part instanceof CommonNavigator navigator) {
									viewer = navigator.getCommonViewer();
								} else if (part instanceof PackageExplorerPart explorer) {
									viewer = explorer.getTreeViewer();
								} else {
									continue;
								}
								if (!viewer.getControl().isDisposed()) viewer.refresh();
							}
						}
					}
				});
			}
		});
	}
}
