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
import org.eclipse.ui.statushandlers.StatusManager;

import gama.ui.devtools.GamaDevToolsActivator;
import gama.ui.devtools.util.ResourceUtils;

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
		var menu = manager.createContextMenu(toolbar);
		boolean[] populated = { false };
		manager.addMenuListener(shown -> manager.updateAll(true));
		button.addListener(SWT.Selection, event -> {
			toolbar.getDisplay().asyncExec(() -> {
				if (toolbar.isDisposed()) return;
				if (!populated[0]) {
					service.populateContributionManager(manager, "menu:gama.ui.devtools.menu");
					populated[0] = true;
				}
				manager.updateAll(true);
				if (menu.getItemCount() == 0 || manager.getItems().length <= 1) {
					StatusManager.getManager().handle(ResourceUtils.error(
							"Cannot populate the GAMA toolbar menu. Use the GAMA menu in the menu bar.", null),
							StatusManager.LOG | StatusManager.SHOW);
					return;
				}
				var bounds = button.getBounds();
				menu.setLocation(toolbar.toDisplay(bounds.x, bounds.y + bounds.height));
				menu.setVisible(true);
			});
		});
		toolbar.addListener(SWT.Dispose, event -> {
			service.releaseContributions(manager);
			manager.dispose();
			if (image != null) image.dispose();
		});
		return toolbar;
	}
}
