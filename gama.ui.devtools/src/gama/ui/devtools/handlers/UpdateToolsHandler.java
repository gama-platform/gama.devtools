/*******************************************************************************************************
 *
 * UpdateToolsHandler.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and
 * simulation platform.
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.handlers;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
import org.eclipse.equinox.p2.metadata.ILicense;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.operations.ProvisioningJob;
import org.eclipse.equinox.p2.operations.UpdateOperation;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.equinox.p2.ui.ProvisioningUI;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.statushandlers.StatusManager;

import gama.ui.devtools.install.UpdateSite;

/** Updates only the installed developer tools, without the p2 update wizard. */
public class UpdateToolsHandler extends AbstractHandler {

	private static final String PLUGIN_ID = "gama.ui.devtools";
	private static final String FEATURE_ID = "gama.feature.devtools.feature.group";
	private static final String TITLE = "Update GAMA tools";
	private static final URI REPOSITORY = URI.create("https://gama-platform.org/gama.devtools/");
	private static final AtomicBoolean UPDATING = new AtomicBoolean();

	@Override
	public Object execute(final ExecutionEvent event) throws ExecutionException {
		final Shell shell = HandlerUtil.getActiveShellChecked(event);
		final var display = shell.getDisplay();
		final ProvisioningUI ui = ProvisioningUI.getDefaultUI();
		if (ui == null || ui.getProfileId() == null) {
			throw new ExecutionException("GAMA tools can only be updated in a provisioned Eclipse installation.");
		}
		if (ui.hasScheduledOperations() || !UPDATING.compareAndSet(false, true)) {
			MessageDialog.openInformation(shell, TITLE,
					"Another software installation or update is in progress. Please finish it and try again.");
			return null;
		}

		final AtomicBoolean updateScheduled = new AtomicBoolean();
		final Job check = Job.create("Checking for GAMA tools updates", monitor -> {
			try {
				final SubMonitor progress = SubMonitor.convert(monitor, 100);
				final var agent = ui.getSession().getProvisioningAgent();
				final var registry = agent.getService(IProfileRegistry.class);
				final var profile = registry == null ? null : registry.getProfile(ui.getProfileId());
				if (profile == null) return error("The Eclipse installation profile is unavailable.");
				final var installed = profile.query(QueryUtil.createIUQuery(FEATURE_ID), progress.split(5));
				if (installed.isEmpty()) return error(
						"GAMA Developer Tools is not installed as a feature. Install it from " + REPOSITORY
						+ " before using this command; workspace and dropins plugins cannot be updated this way.");

				final var metadata = agent.getService(IMetadataRepositoryManager.class);
				final var artifacts = agent.getService(IArtifactRepositoryManager.class);
				if (metadata == null || artifacts == null) return error("Eclipse provisioning services are unavailable.");
				ui.getRepositoryTracker().addRepository(REPOSITORY, "GAMA Developer Tools", ui.getSession());
				// Refreshing p2 alone can still read GitHub Pages' cached mutable composite descriptor.
				final URI[] releases = UpdateSite.releases(REPOSITORY, progress.split(10));
				IInstallableUnit newest = null;
				URI newestRepository = null;
				final SubMonitor loading = progress.split(30).setWorkRemaining(releases.length);
				for (URI release : releases) {
					final var repository = metadata.loadRepository(release, loading.split(1));
					for (IInstallableUnit candidate : repository.query(QueryUtil.createIUQuery(FEATURE_ID), null)) {
						if (newest == null || candidate.getVersion().compareTo(newest.getVersion()) > 0) {
							newest = candidate;
							newestRepository = release;
						}
					}
				}
				if (newest == null) return error("The update site does not publish GAMA Developer Tools: " + REPOSITORY);
				final var installedVersion = installed.toSet().stream().map(IInstallableUnit::getVersion)
						.max(java.util.Comparator.naturalOrder()).orElseThrow();
				final var availableVersion = newest.getVersion();
				artifacts.loadRepository(newestRepository, progress.split(5));
				final UpdateOperation operation = ui.getUpdateOperation(List.copyOf(installed.toSet()),
						new URI[] { newestRepository });
				final IStatus resolution = operation.resolveModal(progress.split(50));
				if (resolution.getCode() == UpdateOperation.STATUS_NOTHING_TO_UPDATE) {
					if (availableVersion.compareTo(installedVersion) > 0) {
						return error("GAMA Developer Tools " + availableVersion + " is published, but Eclipse did not "
								+ "find an applicable update for installed version " + installedVersion
								+ ". Use Help > Install New Software... with " + REPOSITORY
								+ " to inspect the installation requirements.");
					}
					display.syncExec(() -> {
						if (!shell.isDisposed()) MessageDialog.openInformation(shell, TITLE,
								"GAMA tools are up to date.\nInstalled: " + installedVersion
								+ "\nLatest published: " + availableVersion);
					});
					return Status.OK_STATUS;
				}
				if (resolution.matches(IStatus.ERROR | IStatus.CANCEL)) return resolution;
				if (monitor.isCanceled()) return Status.CANCEL_STATUS;
				final ProvisioningJob install = operation.getProvisioningJob(null);
				if (install == null) return error("Eclipse could not create the GAMA tools update job.");
				final Set<ILicense> licenses = new LinkedHashSet<>();
				final var licenseManager = ui.getLicenseManager();
				for (final var unit : operation.getProvisioningPlan().getAdditions().query(
						QueryUtil.createIUAnyQuery(), null)) {
					for (final ILicense license : unit.getLicenses()) {
						if (licenseManager == null || !licenseManager.isAccepted(license)) licenses.add(license);
					}
				}
				install.setName("Updating GAMA tools");
				install.addJobChangeListener(new JobChangeAdapter() {
					@Override
					public void done(final IJobChangeEvent change) {
						UPDATING.set(false);
					}
				});
				display.syncExec(() -> {
					if (shell.isDisposed() || monitor.isCanceled()) return;
					if (ui.hasScheduledOperations()) {
						MessageDialog.openInformation(shell, TITLE,
								"Another software operation has started. Please finish it and try again.");
						return;
					}
					for (final ILicense license : licenses) {
						if (!MessageDialog.openQuestion(shell, TITLE,
								"The update requires acceptance of this license:\n\n" + license.getBody()
								+ "\n\nDo you accept this license?")) return;
					}
					if (licenseManager != null) licenses.forEach(licenseManager::accept);
					ui.schedule(install, StatusManager.LOG | StatusManager.SHOW);
					updateScheduled.set(true);
				});
				return Status.OK_STATUS;
			} catch (final OperationCanceledException e) {
				return Status.CANCEL_STATUS;
			} catch (final ProvisionException | IOException e) {
				return new Status(IStatus.ERROR, PLUGIN_ID,
						"Unable to update GAMA tools. Check your network connection and the update site.", e);
			}
		});
		check.addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(final IJobChangeEvent change) {
				if (!updateScheduled.get()) UPDATING.set(false);
				if (!change.getResult().isOK() && !change.getResult().matches(IStatus.CANCEL)) {
					StatusManager.getManager().handle(change.getResult(), StatusManager.LOG | StatusManager.SHOW);
				}
			}
		});
		check.setUser(true);
		check.schedule();
		return null;
	}

	private static IStatus error(final String message) {
		return new Status(IStatus.ERROR, PLUGIN_ID, message);
	}
}
