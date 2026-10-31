/*******************************************************************************************************
 *
 * GamaStartupMonitor.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and simulation
 * platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.launch;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;

import gama.ui.devtools.jobs.CleanAndRebuildJob;
import gama.ui.devtools.util.GamaProjects;
import gama.ui.devtools.util.ResourceUtils;

/**
 * Watches GAMA UI process exit statuses, including launches made through Eclipse's Run/Debug actions.
 * No console output is interpreted: optional plugin failures must never trigger recovery.
 */
public final class GamaStartupMonitor implements ILaunchesListener2, IDebugEventSetListener, IStartup {

	/**
	 * Cross-repository contract: gama.api.additions.GamaBundleLoader.EXIT_GAML_INITIALIZATION_FAILED in
	 * gama-platform/gama reserves 42 for fatal mandatory GAML API/core initialization.
	 * Kept here to avoid a dependency on the launched runtime.
	 */
	private static final int GAML_INITIALIZATION_FAILED = 42;

	private static GamaStartupMonitor installed;

	private final ConcurrentHashMap<ILaunch, LaunchState> launches = new ConcurrentHashMap<>();
	private volatile boolean active = true;

	private static final class LaunchState {
		final ILaunchConfiguration configuration;
		final String mode;
		final Set<IProcess> inspected = ConcurrentHashMap.newKeySet();
		final AtomicBoolean handled = new AtomicBoolean();

		LaunchState(final ILaunch launch) {
			configuration = launch.getLaunchConfiguration();
			mode = launch.getLaunchMode();
		}
	}

	@Override
	public void earlyStartup() {
		install();
	}

	/** Starts watching launches (idempotent). */
	public static synchronized void install() {
		if (installed != null || DebugPlugin.getDefault() == null) return;
		installed = new GamaStartupMonitor();
		var manager = DebugPlugin.getDefault().getLaunchManager();
		manager.addLaunchListener(installed);
		DebugPlugin.getDefault().addDebugEventListener(installed);
		installed.launchesAdded(manager.getLaunches());
	}

	public static synchronized void uninstall() {
		if (installed == null) return;
		installed.active = false;
		if (DebugPlugin.getDefault() != null) {
			DebugPlugin.getDefault().getLaunchManager().removeLaunchListener(installed);
			DebugPlugin.getDefault().removeDebugEventListener(installed);
		}
		installed.launches.clear();
		installed = null;
	}

	@Override
	public void launchesAdded(final ILaunch[] launches) {
		for (ILaunch launch : launches) { watch(launch); }
	}

	@Override
	public void launchesChanged(final ILaunch[] launches) {
		launchesAdded(launches);
	}

	@Override
	public synchronized void launchesRemoved(final ILaunch[] launches) {
		for (ILaunch launch : launches) {
			inspect(launch, this.launches.remove(launch));
		}
	}

	@Override
	public void launchesTerminated(final ILaunch[] launches) {
		launchesAdded(launches);
	}

	@Override
	public void handleDebugEvents(final DebugEvent[] events) {
		for (DebugEvent event : events) {
			if (event.getSource() instanceof IProcess process) {
				ILaunch launch = process.getLaunch();
				inspectProcess(launch, launches.get(launch), process);
			}
		}
	}

	private synchronized void watch(final ILaunch launch) {
		if (!active) return;
		LaunchState state = launches.get(launch);
		try {
			var debug = DebugPlugin.getDefault();
			if (debug == null) return;
			if (state == null && !java.util.Arrays.asList(
					debug.getLaunchManager().getLaunches()).contains(launch)) return;
			if (state == null && GamaLaunches.isGui(launch.getLaunchConfiguration())) {
				state = launches.computeIfAbsent(launch, LaunchState::new);
			}
		} catch (CoreException e) {
			ResourceUtils.log(e);
		}
		// Re-scan on launch changes and after listener installation, even if the process has already exited.
		inspect(launch, state);
	}

	private void inspect(final ILaunch launch, final LaunchState state) {
		if (!active || state == null || state.handled.get()) return;
		for (IProcess process : launch.getProcesses()) {
			inspectProcess(launch, state, process);
		}
	}

	private void inspectProcess(final ILaunch launch, final LaunchState state, final IProcess process) {
		if (!active || state == null || state.handled.get()
				|| !process.isTerminated() || state.inspected.contains(process)) return;
		try {
			int exit = process.getExitValue();
			state.inspected.add(process);
			if (exit == GAML_INITIALIZATION_FAILED && state.handled.compareAndSet(false, true)) {
				failed(launch, state);
			}
		} catch (DebugException e) {
			ResourceUtils.log(e);
		}
	}

	private void failed(final ILaunch launch, final LaunchState state) {
		onWorkbench(workbench -> {
			MessageDialog dialog = new MessageDialog(workbench.getModalDialogShellProvider().getShell(),
					"GAMA could not start correctly", null,
					"'" + state.configuration.getName() + "' exited with status " + GAML_INITIALIZATION_FAILED
							+ ": mandatory GAML API/core initialization failed. GamlAdditions may be stale "
							+ "or missing after a change in the sources.\n\n"
							+ "Clean and rebuild the workspace (as 'Clean GAMA plugins' does), then relaunch "
							+ "GAMA if the build succeeds?",
					MessageDialog.ERROR, 0, "Clean and Relaunch", "Clean Only", "Ignore");
			int choice = dialog.open();
			if (choice != 0 && choice != 1) return;
			if (workbench.isClosing() || !workbench.saveAllEditors(true)) return;
			Job prepare = Job.create("Stop GAMA before recovery", monitor -> {
				if (!active) return Status.CANCEL_STATUS;
				IStatus result = terminate(launch, monitor);
				if (result.isOK()) {
					onWorkbench(wb -> {
						if (monitor.isCanceled()) return;
						CleanAndRebuildJob job = new CleanAndRebuildJob(GamaProjects.all(), true);
						job.addJobChangeListener(relaunchAfter(job, state, choice == 0));
						job.schedule();
					});
				} else if (result.matches(IStatus.ERROR)) {
					ResourceUtils.log(new CoreException(result));
					report("GAMA recovery stopped", result.getMessage());
				}
				return result;
			});
			prepare.setUser(true);
			prepare.schedule();
		});
	}

	private static IStatus terminate(final ILaunch launch, final IProgressMonitor monitor) {
		try {
			if (monitor.isCanceled()) return Status.CANCEL_STATUS;
			if (!stopped(launch)) {
				if (!launch.canTerminate()) {
					return ResourceUtils.error("The failed GAMA launch cannot be stopped. Stop it before cleaning.", null);
				}
				launch.terminate();
			}
			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
			while (!stopped(launch)) {
				if (monitor.isCanceled()) return Status.CANCEL_STATUS;
				if (System.nanoTime() >= deadline) {
					return ResourceUtils.error("GAMA has not stopped. Stop the launch before cleaning.", null);
				}
				Thread.sleep(100);
			}
			return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
		} catch (DebugException e) {
			return ResourceUtils.error("Cannot stop the failed GAMA launch.", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Status.CANCEL_STATUS;
		}
	}

	private static boolean stopped(final ILaunch launch) {
		if (!launch.isTerminated()) return false;
		for (IProcess process : launch.getProcesses()) {
			if (!process.isTerminated()) return false;
		}
		return true;
	}

	private JobChangeAdapter relaunchAfter(final CleanAndRebuildJob job, final LaunchState state,
			final boolean relaunch) {
		return new JobChangeAdapter() {
			@Override
			public void done(final IJobChangeEvent event) {
				IStatus result = event.getResult();
				if (result.matches(IStatus.CANCEL)) return;
				if (!result.isOK()) {
					ResourceUtils.log(new CoreException(result));
					report("GAMA recovery stopped", result.getMessage());
					return;
				}
				if (!relaunch) return;
				if (!job.succeeded()) {
					report("GAMA not relaunched",
							"The GAMA plugins still have build problems. Fix them, then relaunch GAMA.");
					return;
				}
				onWorkbench(workbench -> {
					if (!state.configuration.exists()) {
						MessageDialog.openError(workbench.getModalDialogShellProvider().getShell(),
								"GAMA not relaunched", "The original launch configuration no longer exists.");
						return;
					}
					DebugUITools.launch(state.configuration, state.mode);
				});
			}
		};
	}

	private void report(final String title, final String message) {
		onWorkbench(workbench -> MessageDialog.openError(workbench.getModalDialogShellProvider().getShell(),
				title, message));
	}

	private void onWorkbench(final Consumer<IWorkbench> action) {
		if (!active || !PlatformUI.isWorkbenchRunning()) return;
		IWorkbench workbench = PlatformUI.getWorkbench();
		var display = workbench.getDisplay();
		if (display.isDisposed() || workbench.isClosing()) return;
		try {
			display.asyncExec(() -> {
				if (!active || display.isDisposed() || workbench.isClosing()) return;
				var shell = workbench.getModalDialogShellProvider().getShell();
				if (shell == null || shell.isDisposed()) return;
				action.accept(workbench);
			});
		} catch (SWTException e) {
			if (e.code != SWT.ERROR_DEVICE_DISPOSED) throw e;
			ResourceUtils.log(e);
		}
	}
}
