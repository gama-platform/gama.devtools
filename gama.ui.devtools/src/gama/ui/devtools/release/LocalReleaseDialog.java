package gama.ui.devtools.release;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import gama.ui.devtools.launch.GamaLaunches;
import gama.ui.devtools.preferences.DevToolsPreferences;
import gama.ui.devtools.util.GamaProjects;

/** Chooses build inputs once; a release always targets the current Eclipse environment. */
public final class LocalReleaseDialog extends TitleAreaDialog {

	private final LocalReleasePlan.Target target;
	private final List<Path> jdks = new ArrayList<>();
	private final List<String> jdkNames = new ArrayList<>();
	private Combo jdk;
	private LocalReleasePlan result;

	public LocalReleaseDialog(Shell shell, LocalReleasePlan.Target target) {
		super(shell);
		this.target = target;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	public LocalReleasePlan result() { return result; }

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		setTitle("Build a local GAMA release");
		setMessage("The release will be built using the plugins present in the workspace. "
				+ "The first build might take more time. "
				+ "Note that this produces an unsigned application, which can only be run locally");
		Composite content = new Composite(area, SWT.NONE);
		content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		content.setLayout(new GridLayout(3, false));
		addJdk(Path.of(System.getProperty("java.home")), "Eclipse runtime");
		for (var vm : GamaLaunches.jdks()) addJdk(vm.getInstallLocation().toPath(), vm.getName());
		if (jdks.size() == 1) return area;
		new Label(content, SWT.NONE).setText("JDK 25:");
		jdk = new Combo(content, SWT.READ_ONLY);
		GridData jdkData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		jdkData.widthHint = 440;
		jdk.setLayoutData(jdkData);
		jdk.setItems(jdkNames.toArray(String[]::new));
		if (!jdks.isEmpty()) jdk.select(0);
		Button browse = new Button(content, SWT.PUSH);
		browse.setText("Browse...");
		browse.addListener(SWT.Selection, e -> {
			DirectoryDialog dialog = new DirectoryDialog(getShell());
			dialog.setText("Select a full JDK 25 home (Contents/Home on macOS)");
			String selected = dialog.open();
			if (selected == null) return;
			try {
				Path path = Path.of(selected).toRealPath();
				LocalReleasePlan.validateJdk(path, target);
				addJdk(path, "Selected JDK");
				jdk.select(jdks.indexOf(path));
				setErrorMessage(null);
			} catch (java.io.IOException | IllegalArgumentException ex) {
				setErrorMessage(ex.getMessage());
			}
		});
		return area;
	}

	private void addJdk(Path path, String name) {
		try {
			path = path.toRealPath();
			LocalReleasePlan.validateJdk(path, target);
			if (jdks.contains(path)) return;
			jdks.add(path);
			String label = name + " - " + path;
			jdkNames.add(label);
			if (jdk != null) jdk.add(label);
		} catch (java.io.IOException e) {
			// A JRE, incompatible JDK or stale Eclipse entry is not a selectable build JDK.
		}
	}

	@Override
	protected void okPressed() {
		try {
			var project = GamaProjects.root().getProject(GamaProjects.PARENT_PROJECT);
			if (!project.exists() || project.getLocation() == null) {
				throw new IllegalArgumentException("Import gama.parent into the workspace before building a release.");
			}
			int jdkIndex = jdk == null ? 0 : jdk.getSelectionIndex();
			if (jdkIndex < 0 || jdks.isEmpty()) throw new IllegalArgumentException("Select or browse to a full JDK 25.");
			Path checkout = project.getLocation().removeLastSegments(1).toFile().toPath().toRealPath();
			DirectoryDialog destination = new DirectoryDialog(getShell());
			destination.setText("Save local GAMA release");
			destination.setMessage("Choose a destination outside the GAMA source checkout.");
			destination.setFilterPath(DevToolsPreferences.get("release.destination"));
			String selected = destination.open();
			if (selected == null) return;
			result = new LocalReleasePlan(checkout, jdks.get(jdkIndex),
					Path.of(selected).toRealPath(), target);
			result.validate();
			DevToolsPreferences.store().setValue("release.destination", result.destination().toString());
			super.okPressed();
		} catch (Exception e) {
			setErrorMessage(e.getMessage());
		}
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, "Build release...", true);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
	}
}
