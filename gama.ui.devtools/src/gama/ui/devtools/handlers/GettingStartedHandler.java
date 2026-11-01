/*******************************************************************************************************
 *
 * GettingStartedHandler.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and
 * simulation platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.handlers.HandlerUtil;

import gama.ui.devtools.wizards.GettingStartedWizard;

/** Opens the step-by-step setup of a GAMA development environment. */
public final class GettingStartedHandler extends AbstractHandler {

	@Override
	public Object execute(final ExecutionEvent event) {
		GettingStartedWizard.open(HandlerUtil.getActiveShell(event));
		return null;
	}
}
