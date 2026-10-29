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
import org.eclipse.swt.widgets.Text;

import gama.ui.devtools.launch.GamaLaunches;
import gama.ui.devtools.preferences.DevToolsPreferences;
import gama.ui.devtools.util.GamaProjects;

/** Chooses build inputs once; a release always targets the current Eclipse environment. */
public final class LocalReleaseDialog extends TitleAreaDialog {

	private final LocalReleasePlan.Target target;
	private final List<Path> jdks = new ArrayList<>();
	private Text source;
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
		setMessage("Build only for " + target.id() + ". Maven is downloaded automatically; no installation is needed.");
		Composite content = new Composite(area, SWT.NONE);
		content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		content.setLayout(new GridLayout(3, false));
		source = directory(content, "GAMA source checkout:", DevToolsPreferences.get("release.source"));
		if (source.getText().isBlank()) {
			var project = GamaProjects.root().getProject(GamaProjects.PARENT_PROJECT);
			if (project.exists() && project.getLocation() != null) {
				source.setText(project.getLocation().removeLastSegments(1).toOSString());
			}
		}
		new Label(content, SWT.NONE).setText("JDK 25:");
		jdk = new Combo(content, SWT.READ_ONLY);
		jdk.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		addJdk(Path.of(System.getProperty("java.home")), "Eclipse runtime");
		for (var vm : GamaLaunches.jdks()) addJdk(vm.getInstallLocation().toPath(), vm.getName());
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
		Label explanation = new Label(content, SWT.WRAP);
		GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1);
		data.widthHint = 650;
		explanation.setLayoutData(data);
		explanation.setText("Eclipse's runtime is used when it is a full JDK 25; otherwise choose a configured JDK "
				+ "or browse to one. The selected JDK is copied into the release so it runs without Eclipse.\n\n"
				+ "A complete source snapshot is built, including saved local changes. Your checkout is not modified. "
				+ "Save outside the source checkout; existing releases are never overwritten.\n\n"
				+ "The first build downloads Maven and GAMA's dependencies and can take considerable time and disk space. "
				+ "Progress and cancellation are available in Eclipse; full output is shown in the Console. "
				+ "This produces an unsigned runnable folder, not a signed installer.");
		return area;
	}

	private Text directory(Composite parent, String label, String value) {
		new Label(parent, SWT.NONE).setText(label);
		Text text = new Text(parent, SWT.BORDER);
		GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
		data.widthHint = 440;
		text.setLayoutData(data);
		text.setText(value);
		Button browse = new Button(parent, SWT.PUSH);
		browse.setText("Browse...");
		browse.addListener(SWT.Selection, e -> {
			DirectoryDialog dialog = new DirectoryDialog(getShell());
			dialog.setText(label);
			dialog.setFilterPath(text.getText());
			String selected = dialog.open();
			if (selected != null) text.setText(selected);
		});
		return text;
	}

	private void addJdk(Path path, String name) {
		try {
			path = path.toRealPath();
			LocalReleasePlan.validateJdk(path, target);
			if (jdks.contains(path)) return;
			jdks.add(path);
			jdk.add(name + " - " + path);
		} catch (java.io.IOException e) {
			// A JRE, incompatible JDK or stale Eclipse entry is not a selectable build JDK.
		}
	}

	@Override
	protected void okPressed() {
		try {
			if (source.getText().isBlank()) {
				throw new IllegalArgumentException("Choose the GAMA source checkout.");
			}
			if (jdk.getSelectionIndex() < 0) throw new IllegalArgumentException("Select or browse to a full JDK 25.");
			Path checkout = Path.of(source.getText()).toRealPath();
			DirectoryDialog destination = new DirectoryDialog(getShell());
			destination.setText("Save local GAMA release");
			destination.setMessage("Choose a destination outside the GAMA source checkout.");
			destination.setFilterPath(DevToolsPreferences.get("release.destination"));
			String selected = destination.open();
			if (selected == null) return;
			result = new LocalReleasePlan(checkout, jdks.get(jdk.getSelectionIndex()),
					Path.of(selected).toRealPath(), target);
			result.validate();
			DevToolsPreferences.store().setValue("release.source", result.source().toString());
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
