/*******************************************************************************************************
 *
 * BranchChangeStartup.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and
 * simulation platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.install;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.ui.IStartup;

import gama.ui.devtools.GamaDevToolsActivator;
import gama.ui.devtools.jobs.CleanAndRebuildJob;
import gama.ui.devtools.util.GamaProjects;

/** Watches the GAMA source repositories for branch switches and rebuilds the workspace. */
public final class BranchChangeStartup implements IStartup {

	private static final long POLL_INTERVAL = 5_000L;
	private static final long SETTLE_INTERVAL = 5_000L;
	private static Job monitor;
	private static CleanAndRebuildJob rebuild;
	private static Map<String, String> branches = Map.of();
	private static boolean initialized;
	private static boolean pendingChange;
	private static long lastChange;
	private static boolean stopped;

	@Override
	public void earlyStartup() {
		synchronized (BranchChangeStartup.class) {
			stopped = false;
			if (monitor != null) return;
			monitor = Job.create("Monitoring GAMA source branches", BranchChangeStartup::poll);
			monitor.setSystem(true);
			monitor.addJobChangeListener(new JobChangeAdapter() {
				@Override
				public void done(final IJobChangeEvent event) {
					logFailure(event.getResult());
					synchronized (BranchChangeStartup.class) {
						if (!stopped) event.getJob().schedule(POLL_INTERVAL);
					}
				}
			});
			monitor.schedule();
		}
	}

	private static IStatus poll(final IProgressMonitor monitor) {
		try {
			Map<String, String> current = branchSnapshot();
			long now = System.currentTimeMillis();
			synchronized (BranchChangeStartup.class) {
				if (!initialized) {
					branches = current;
					initialized = true;
					return Status.OK_STATUS;
				}
				boolean changed = current.entrySet().stream()
						.anyMatch(entry -> branches.containsKey(entry.getKey())
								&& !entry.getValue().equals(branches.get(entry.getKey())));
				branches = current;
				if (changed) {
					pendingChange = true;
					lastChange = now;
				}
				if (pendingChange && now - lastChange >= SETTLE_INTERVAL
						&& (rebuild == null || rebuild.getState() == Job.NONE)) {
					pendingChange = false;
					rebuild = new CleanAndRebuildJob(GamaProjects.all(), true);
					rebuild.addJobChangeListener(new JobChangeAdapter() {
						@Override
						public void done(final IJobChangeEvent event) {
							synchronized (BranchChangeStartup.class) { rebuild = null; }
						}
					});
					rebuild.schedule();
				}
			}
			return Status.OK_STATUS;
		} catch (IOException e) {
			return new Status(IStatus.WARNING, GamaDevToolsActivator.PLUGIN_ID,
					"Cannot check GAMA source branches.", e);
		}
	}

	private static Map<String, String> branchSnapshot() throws IOException {
		Map<String, String> result = new HashMap<>();
		for (IProject project : GamaProjects.all()) {
			var location = project.getLocation();
			if (location == null) continue;
			FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(location.toFile());
			if (builder.getGitDir() == null) continue;
			try (Repository repository = builder.build()) {
				String branch = repository.getFullBranch();
				if (branch != null) {
					result.put(repository.getDirectory().getAbsoluteFile().toPath().normalize().toString(), branch);
				}
			}
		}
		return result;
	}

	private static void logFailure(final IStatus status) {
		var plugin = GamaDevToolsActivator.getInstance();
		if (plugin != null && !status.isOK() && !status.matches(IStatus.CANCEL)) {
			plugin.getLog().log(status);
		}
	}

	public static synchronized void stop() {
		stopped = true;
		if (monitor != null) {
			monitor.cancel();
			monitor = null;
		}
	}
}
