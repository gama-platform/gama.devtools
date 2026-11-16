/*******************************************************************************************************
 *
 * DevToolsPreferences.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and simulation
 * platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import gama.ui.devtools.GamaDevToolsActivator;

/**
 * The preferences of the developer tools, and their default values.
 */
public class DevToolsPreferences extends AbstractPreferenceInitializer {

	/** Vendor used by the new plugin wizard. */
	public static final String DEFAULT_VENDOR = "gama.devtools.defaultVendor";

	/** Prefix of the plugin ids proposed by the new plugin wizard. */
	public static final String DEFAULT_PREFIX = "gama.devtools.defaultPrefix";

	/** Whether to offer the Get started wizard when the workspace has no GAMA sources (stored per workspace). */
	public static final String GETTING_STARTED_PROMPT = "gama.devtools.gettingStartedPrompt";

	/** Whether to confirm before replacing an existing local GAMA release. */
	public static final String CONFIRM_RELEASE_REPLACEMENT = "gama.devtools.confirmReleaseReplacement";

	/** Whether to ask before rebuilding the annotation processor when cleaning and building all GAMA plugins. */
	public static final String ASK_REBUILD_PROCESSOR = "gama.devtools.askRebuildProcessor";

	/** Whether cleaning and building all GAMA plugins should rebuild the annotation processor. */
	public static final String REBUILD_PROCESSOR_ON_CLEAN = "gama.devtools.rebuildProcessorOnClean";

	@Override
	public void initializeDefaultPreferences() {
		IPreferenceStore store = store();
		store.setDefault(DEFAULT_VENDOR, "UMMISCO");
		store.setDefault(DEFAULT_PREFIX, "gama.extension.");
		store.setDefault(GETTING_STARTED_PROMPT, true);
		store.setDefault(CONFIRM_RELEASE_REPLACEMENT, true);
		store.setDefault(ASK_REBUILD_PROCESSOR, true);
		store.setDefault(REBUILD_PROCESSOR_ON_CLEAN, true);
	}

	/** The preference store of the plugin. */
	public static IPreferenceStore store() {
		return GamaDevToolsActivator.getInstance().getPreferenceStore();
	}

	/** Shortcut for boolean preferences. */
	public static boolean is(final String key) {
		return store().getBoolean(key);
	}

	/** Shortcut for string preferences. */
	public static String get(final String key) {
		return store().getString(key);
	}

}
