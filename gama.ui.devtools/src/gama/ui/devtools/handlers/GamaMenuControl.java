package gama.ui.devtools.handlers;

import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.menus.IMenuService;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import gama.ui.devtools.GamaDevToolsActivator;

/** Opens the same contributed commands as the main GAMA menu. */
public final class GamaMenuControl extends WorkbenchWindowControlContribution {

	@Override
	protected Control createControl(Composite parent) {
		ToolBar toolbar = new ToolBar(parent, SWT.FLAT);
		ToolItem button = new ToolItem(toolbar, SWT.PUSH);
		button.setToolTipText("GAMA menu");
		var descriptor = AbstractUIPlugin.imageDescriptorFromPlugin(GamaDevToolsActivator.PLUGIN_ID, "icons/gama.png");
		var image = descriptor == null ? null : descriptor.createImage();
		if (image == null) button.setText("GAMA");
		else button.setImage(image);

		IMenuService service = getWorkbenchWindow().getService(IMenuService.class);
		MenuManager manager = new MenuManager("GAMA", "gama.ui.devtools.menu");
		manager.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
		service.populateContributionManager(manager, "menu:gama.ui.devtools.menu");
		var menu = manager.createContextMenu(toolbar);
		manager.addMenuListener(shown -> manager.updateAll(true));
		button.addListener(SWT.Selection, event -> {
			var bounds = button.getBounds();
			menu.setLocation(toolbar.toDisplay(bounds.x, bounds.y + bounds.height));
			menu.setVisible(true);
		});
		toolbar.addListener(SWT.Dispose, event -> {
			service.releaseContributions(manager);
			manager.dispose();
			if (image != null) image.dispose();
		});
		return toolbar;
	}
}
