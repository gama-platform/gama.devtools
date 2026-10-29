package gama.ui.devtools.handlers;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.statushandlers.StatusManager;

import gama.ui.devtools.GamaDevToolsActivator;
import gama.ui.devtools.release.LocalReleaseBuilder;
import gama.ui.devtools.release.LocalReleaseDialog;
import gama.ui.devtools.release.LocalReleasePlan;
import gama.ui.devtools.util.ResourceUtils;

/** Builds GAMA without requiring a system Maven or changing the user's source tree. */
public final class BuildLocalReleaseHandler extends AbstractHandler {

	private static final AtomicBoolean BUILDING = new AtomicBoolean();
	private static final String TITLE = "GAMA Local Release";

	@Override
	public Object execute(ExecutionEvent event) {
		var window = HandlerUtil.getActiveWorkbenchWindow(event);
		if (window == null) return null;
		var shell = window.getShell();
		if (BUILDING.get()) {
			MessageDialog.openInformation(shell, TITLE, "A local GAMA release build is already in progress.");
			return null;
		}
		try {
			var target = new LocalReleasePlan.Target(Platform.getOS(), Platform.getWS(), Platform.getOSArch());
			LocalReleaseDialog dialog = new LocalReleaseDialog(shell, target);
			if (dialog.open() != Window.OK || !window.getWorkbench().saveAllEditors(true)) return null;
			if (!BUILDING.compareAndSet(false, true)) return null;
			var plan = dialog.result();
			MessageConsole console = new MessageConsole(TITLE, null);
			var manager = ConsolePlugin.getDefault().getConsoleManager();
			manager.addConsoles(new IConsole[] { console });
			manager.showConsoleView(console);
			var display = shell.getDisplay();
			Path state = GamaDevToolsActivator.getInstance().getStateLocation().toPath().resolve("local-releases");
			Job job = Job.create("Build local GAMA release for " + target.id(), monitor -> {
				monitor.beginTask("Building local GAMA release", org.eclipse.core.runtime.IProgressMonitor.UNKNOWN);
				Path log = null;
				try (var output = new PrintStream(console.newMessageStream(), true, StandardCharsets.UTF_8)) {
					Files.createDirectories(state);
					log = Files.createTempFile(state, "build-", ".log");
					Path buildLog = log;
					output.println("Source: " + plan.source() + "\nTarget: " + target.id() + "\nJDK: " + plan.jdk()
							+ "\nDestination: " + plan.destination() + "\nBuild log: " + log);
					Path release = LocalReleaseBuilder.build(plan, state, log, monitor::isCanceled, message -> {
						monitor.subTask(message);
						output.println("\n" + message);
						try {
							Files.writeString(buildLog, "\n" + message + "\n", StandardCharsets.UTF_8,
									java.nio.file.StandardOpenOption.APPEND);
						} catch (java.io.IOException e) {
							throw new java.io.UncheckedIOException("Cannot write the build log.", e);
						}
					}, output::print);
					output.println("\nRelease saved in " + release);
					display.asyncExec(() -> {
						if (shell.isDisposed()) return;
						MessageDialog success = new MessageDialog(shell, TITLE, null,
								"The local release was saved in:\n" + release + "\n\n"
								+ "It includes JDK 25 and does not need Eclipse or Maven to run."
								+ (target.mac() ? "\nThis is an unsigned local build, not a notarized distribution." : "")
								+ "\n\nRun the produced application now?",
								MessageDialog.INFORMATION, new String[] { "Run GAMA", IDialogConstants.CLOSE_LABEL }, 1);
						if (success.open() == 0) launchRelease(release, target);
					});
					return Status.OK_STATUS;
				} catch (CancellationException e) {
					return Status.CANCEL_STATUS;
				} catch (Exception e) {
					IStatus status = ResourceUtils.error("Cannot build the local GAMA release: " + e.getMessage()
							+ (log == null ? "" : "\nBuild log: " + log), e);
					StatusManager.getManager().handle(status, StatusManager.LOG | StatusManager.SHOW);
					return status;
				} finally {
					monitor.done();
				}
			});
			job.addJobChangeListener(new JobChangeAdapter() {
				@Override public void done(IJobChangeEvent change) { BUILDING.set(false); }
			});
			job.setUser(true);
			job.schedule();
		} catch (RuntimeException e) {
			BUILDING.set(false);
			ResourceUtils.log(e);
			MessageDialog.openError(shell, TITLE, e.getMessage());
		}
		return null;
	}

	private static void launchRelease(Path release, LocalReleasePlan.Target target) {
		Job launch = Job.create("Run local GAMA release", monitor -> {
			Path log = release.resolve("launch.log");
			List<String> command = target.mac()
					? List.of("/usr/bin/open", "-n", release.resolve("Gama.app").toString())
					: List.of(release.resolve(target.windows() ? "Gama.exe" : "Gama").toString());
			try {
				Process process = new ProcessBuilder(command).directory(release.toFile())
						.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
				process.getOutputStream().close();
				if (target.mac() && process.waitFor() != 0) {
					throw new IOException("macOS could not open Gama.app. See " + log
							+ ". You may need to allow the unsigned app in System Settings > Privacy & Security.");
				}
				return Status.OK_STATUS;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return Status.CANCEL_STATUS;
			} catch (IOException e) {
				IStatus status = ResourceUtils.error("The release was built successfully, but GAMA could not be launched: "
						+ e.getMessage() + "\nRelease: " + release, e);
				StatusManager.getManager().handle(status, StatusManager.LOG | StatusManager.SHOW);
				return status;
			}
		});
		launch.setUser(true);
		launch.schedule();
	}
}
