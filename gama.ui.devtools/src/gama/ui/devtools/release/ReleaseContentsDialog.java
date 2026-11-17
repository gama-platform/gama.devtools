package gama.ui.devtools.release;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import gama.ui.devtools.preferences.DevToolsPreferences;
import gama.ui.devtools.util.ResourceUtils;

/** Checkout-specific release contents; no source files or launch configurations are modified. */
public final class ReleaseContentsDialog extends TitleAreaDialog {

	private final Path source;
	private final ReleaseContents contents;
	private final Set<String> included = new LinkedHashSet<>();
	private final Set<String> excludedPlugins = new LinkedHashSet<>();
	private Table features;
	private Table plugins;
	private Label pluginLabel;
	private Label summary;
	private boolean loaded;

	public ReleaseContentsDialog(final Shell shell, final Path source) throws Exception {
		super(shell);
		setShellStyle(getShellStyle() | SWT.RESIZE);
		this.source = source.toRealPath();
		contents = ReleaseContents.read(this.source);
	}

	public static ReleaseContents.Selection selection(final Path source) throws Exception {
		return ReleaseContents.Selection.decode(DevToolsPreferences.get(key(source.toRealPath())));
	}

	private static String key(final Path source) {
		return "release.contents." + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(source.toString().getBytes(StandardCharsets.UTF_8));
	}

	@Override
	protected void configureShell(final Shell shell) {
		super.configureShell(shell);
		shell.setText("Configure GAMA build");
	}

	@Override
	protected Control createDialogArea(final Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		setTitle("Local release contents");
		setMessage("Choose the features and plugins for local releases of this checkout. Source files stay unchanged.");
		Composite body = new Composite(area, SWT.NONE);
		body.setLayout(new GridLayout(2, true));
		body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		new Label(body, SWT.NONE).setText("Features in the Maven reactor / product:");
		pluginLabel = new Label(body, SWT.NONE);
		pluginLabel.setText("Plugins in the selected feature:");
		features = table(body);
		plugins = table(body);
		for (ReleaseContents.Feature feature : contents.features()) {
			TableItem item = new TableItem(features, SWT.NONE);
			item.setText(feature.id() + (feature.file() == null ? " (target platform)" : ""));
			item.setData(feature);
		}
		features.addListener(SWT.Selection, event -> {
			if (event.detail == SWT.CHECK) {
				TableItem item = (TableItem) event.item;
				ReleaseContents.Feature feature = (ReleaseContents.Feature) item.getData();
				if (item.getChecked()) included.addAll(contents.closure(Set.of(feature.id()), Set.of()));
				else included.removeAll(contents.closure(Set.of(feature.id()), Set.of()));
				refreshChecks();
			}
			showPlugins();
		});
		plugins.addListener(SWT.Selection, event -> {
			if (event.detail != SWT.CHECK || features.getSelectionCount() == 0) return;
			TableItem item = (TableItem) event.item;
			var feature = (ReleaseContents.Feature) features.getSelection()[0].getData();
			String id = feature.id() + "/" + item.getText();
			if (item.getChecked()) excludedPlugins.remove(id);
			else excludedPlugins.add(id);
			updateSummary();
		});
		summary = new Label(body, SWT.WRAP);
		summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		Label warning = new Label(body, SWT.WRAP);
		GridData warningData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
		warningData.widthHint = 760;
		warning.setLayoutData(warningData);
		warning.setText("Checking a feature also includes its nested features; unchecking it removes them. "
				+ "Plugin choices apply to that feature only. Target-platform feature contents cannot be edited.\n"
				+ "Required dependencies may still be resolved by Tycho, or exclusions may make the build fail. "
				+ "Maven modules are kept so other features can still use their dependencies.\n"
				+ "To make another source feature available here, first integrate it using Add to GAMA build... .");
		Button defaults = new Button(body, SWT.PUSH);
		defaults.setText("Restore product defaults");
		defaults.addListener(SWT.Selection, event -> load(ReleaseContents.Selection.DEFAULT));
		try {
			ReleaseContents.Selection saved = selection(source);
			contents.validate(saved);
			load(saved);
		} catch (Exception e) {
			ResourceUtils.log(e);
			setErrorMessage(e.getMessage());
		}
		if (features.getItemCount() > 0) {
			features.select(0);
			showPlugins();
		}
		return area;
	}

	private static Table table(final Composite parent) {
		Table table = new Table(parent, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.SINGLE);
		GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
		data.widthHint = 380;
		data.heightHint = 340;
		table.setLayoutData(data);
		return table;
	}

	private void load(final ReleaseContents.Selection selection) {
		included.clear();
		included.addAll(contents.included(selection));
		excludedPlugins.clear();
		excludedPlugins.addAll(selection.excludedPlugins());
		loaded = true;
		setErrorMessage(null);
		refreshChecks();
		showPlugins();
	}

	private void refreshChecks() {
		for (TableItem item : features.getItems()) {
			item.setChecked(included.contains(((ReleaseContents.Feature) item.getData()).id()));
		}
		updateSummary();
	}

	private void showPlugins() {
		plugins.removeAll();
		if (features.getSelectionCount() == 0) return;
		var feature = (ReleaseContents.Feature) features.getSelection()[0].getData();
		pluginLabel.setText(feature.file() == null ? "Plugins managed by the target platform:" : "Plugins in the selected feature:");
		plugins.setEnabled(loaded && included.contains(feature.id()) && feature.file() != null);
		for (String id : feature.plugins()) {
			TableItem item = new TableItem(plugins, SWT.NONE);
			item.setText(id);
			item.setChecked(!excludedPlugins.contains(feature.id() + "/" + id));
		}
	}

	private void updateSummary() {
		summary.setText(included.size() + " features included; " + excludedPlugins.size() + " plugin entries excluded.");
		Button save = getButton(IDialogConstants.OK_ID);
		if (save != null) save.setEnabled(loaded && !included.isEmpty());
	}

	@Override
	protected void createButtonsForButtonBar(final Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, "Save configuration", true);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
		updateSummary();
	}

	@Override
	protected void okPressed() {
		try {
			ReleaseContents.Selection selected = contents.selection(included, excludedPlugins);
			contents.validate(selected);
			if ((!selected.excludedFeatures().isEmpty() || !selected.excludedPlugins().isEmpty())
					&& !MessageDialog.openConfirm(getShell(), "Save local build exclusions",
							"Excluded features or plugins may be required by GAMA or another feature. "
							+ "Tycho may resolve them as dependencies, or the release build may fail.\n\n"
							+ "Save these choices for local release builds only?")) return;
			DevToolsPreferences.store().setValue(key(source), selected.encode());
			super.okPressed();
		} catch (Exception e) {
			ResourceUtils.log(e);
			setErrorMessage(e.getMessage());
		}
	}
}
