package gama.ui.devtools.install;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.equinox.p2.ui.ProvisioningUI;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

import gama.ui.devtools.GamaDevToolsActivator;

/** Checks availability only; installation always requires the user's update command. */
public final class UpdateCheckStartup implements IStartup {

	private static final long INTERVAL = 60 * 60 * 1000L;
	private static volatile boolean updateAvailable;
	private static Job job;
	private static boolean stopped;

	public static boolean isUpdateAvailable() { return updateAvailable; }

	public static void setUpdateAvailable(boolean available) {
		if (updateAvailable == available) return;
		updateAvailable = available;
		if (!PlatformUI.isWorkbenchRunning()) return;
		var workbench = PlatformUI.getWorkbench();
		var display = workbench.getDisplay();
		if (display.isDisposed()) return;
		display.asyncExec(() -> {
			if (display.isDisposed() || workbench.isClosing()) return;
			var commands = workbench.getService(ICommandService.class);
			if (commands != null) commands.refreshElements("gama.ui.devtools.updateTools", null);
		});
	}

	@Override
	public void earlyStartup() {
		synchronized (UpdateCheckStartup.class) {
			if (stopped || job != null) return;
			job = Job.create("Checking for GAMA tools updates", monitor -> {
				try {
					var ui = ProvisioningUI.getDefaultUI();
					if (ui == null || ui.getProfileId() == null || ui.hasScheduledOperations()) return Status.OK_STATUS;
					var progress = SubMonitor.convert(monitor, 100);
					var agent = ui.getSession().getProvisioningAgent();
					var registry = agent.getService(IProfileRegistry.class);
					var profile = registry == null ? null : registry.getProfile(ui.getProfileId());
					if (profile == null) return Status.OK_STATUS;
					var installed = profile.query(QueryUtil.createIUQuery(UpdateSite.FEATURE_ID), progress.split(5));
					// Workspace launches and dropins plugins cannot be updated through p2.
					if (installed.isEmpty()) return Status.OK_STATUS;
					var metadata = agent.getService(IMetadataRepositoryManager.class);
					if (metadata == null) return new Status(IStatus.WARNING, GamaDevToolsActivator.PLUGIN_ID,
							"Cannot check for GAMA tools updates: Eclipse provisioning services are unavailable.");
					var latest = UpdateSite.latest(metadata, progress.split(95));
					var installedVersion = installed.toSet().stream().map(IInstallableUnit::getVersion)
							.max(java.util.Comparator.naturalOrder()).orElseThrow();
					if (monitor.isCanceled()) return Status.CANCEL_STATUS;
					setUpdateAvailable(latest.unit().getVersion().compareTo(installedVersion) > 0);
					return Status.OK_STATUS;
				} catch (OperationCanceledException e) {
					return Status.CANCEL_STATUS;
				} catch (java.io.IOException | ProvisionException e) {
					return new Status(IStatus.WARNING, GamaDevToolsActivator.PLUGIN_ID,
							"Cannot check for GAMA tools updates. Check your network connection and the update site.", e);
				}
			});
			job.setSystem(true);
			job.addJobChangeListener(new JobChangeAdapter() {
				@Override
				public void done(IJobChangeEvent event) {
					var status = event.getResult();
					var plugin = GamaDevToolsActivator.getInstance();
					if (plugin != null && !status.isOK() && !status.matches(IStatus.CANCEL)) {
						plugin.getLog().log(status);
					}
					synchronized (UpdateCheckStartup.class) {
						if (!stopped) event.getJob().schedule(INTERVAL);
					}
				}
			});
			job.schedule();
		}
	}

	public static synchronized void stop() {
		stopped = true;
		if (job != null) job.cancel();
	}
}
