package gama.ui.devtools.release;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Shell;

import gama.ui.devtools.launch.GamaLaunches;
import gama.ui.devtools.preferences.DevToolsPreferences;
import gama.ui.devtools.util.GamaProjects;

/** Chooses the destination using the native dialog for the current environment. */
public final class LocalReleaseDialog {

	private final Shell shell;
	private final LocalReleasePlan.Target target;
	private LocalReleasePlan result;

	public LocalReleaseDialog(Shell shell, LocalReleasePlan.Target target) {
		this.shell = shell;
		this.target = target;
	}

	public LocalReleasePlan result() { return result; }

	public int open() {
		try {
			var project = GamaProjects.root().getProject(GamaProjects.PARENT_PROJECT);
			if (!project.exists() || project.getLocation() == null) {
				throw new IllegalArgumentException("Import gama.parent into the workspace before building a release.");
			}
			Path checkout = project.getLocation().removeLastSegments(1).toFile().toPath().toRealPath();
			Path jdk = selectJdk();
			DirectoryDialog destination = new DirectoryDialog(shell);
			destination.setText("Save local GAMA release");
			destination.setMessage("Build a local release of GAMA.\n"
					+ "To integrate a custom plugin, use the navigator context menu \"GAMA > Add to GAMA Build...\".");
			destination.setFilterPath(DevToolsPreferences.get("release.destination"));
			String selected = destination.open();
			if (selected == null) return Window.CANCEL;
			LocalReleasePlan plan = new LocalReleasePlan(checkout, jdk, Path.of(selected).toRealPath(), target);
			plan.validate();
			DevToolsPreferences.store().setValue("release.destination", plan.destination().toString());
			result = plan;
			return Window.OK;
		} catch (Exception e) {
			MessageDialog.openError(shell, "Build a local GAMA release", e.getMessage());
			return Window.CANCEL;
		}
	}

	private Path selectJdk() throws IOException {
		List<Path> candidates = new ArrayList<>();
		candidates.add(Path.of(System.getProperty("java.home")));
		for (var vm : GamaLaunches.jdks()) candidates.add(vm.getInstallLocation().toPath());
		List<IOException> failures = new ArrayList<>();
		for (Path candidate : candidates) {
			try {
				Path home = candidate.toRealPath();
				LocalReleasePlan.validateJdk(home, target);
				return home;
			} catch (IOException e) {
				failures.add(e);
			}
		}
		IOException error = new IOException("No compatible full JDK 25 is available. "
				+ "Add one under Preferences > Java > Installed JREs before building a release.");
		failures.forEach(error::addSuppressed);
		throw error;
	}
}
