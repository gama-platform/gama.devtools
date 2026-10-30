/*******************************************************************************************************
 *
 * GamaProjectTester.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and simulation
 * platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.util;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.core.resources.IProject;

/** Tests GAMA project properties used by context-menu contributions. */
public class GamaProjectTester extends PropertyTester {

	@Override
	public boolean test(final Object receiver, final String property, final Object[] args,
			final Object expectedValue) {
		if (!(receiver instanceof IProject project)) return false;
		if ("gamaPlugin".equals(property)) return GamaProjects.isGamaPlugin(project);
		if ("needsBuildIntegration".equals(property) && GamaProjects.isGamaPlugin(project)) {
			try {
				var analysis = GamaBuildIntegration.analyze(project);
				if (!analysis.pluginMavenIntegrated()) return true;
				for (var feature : analysis.containingFeatures()) {
					if (GamaBuildIntegration.mavenIntegrated(feature.project())
							&& GamaBuildIntegration.reachable(feature.id(), analysis.productFeatures())) return false;
				}
				return true;
			} catch (org.eclipse.core.runtime.CoreException e) {
				ResourceUtils.log(e);
				return true;
			}
		}
		return false;
	}

}
