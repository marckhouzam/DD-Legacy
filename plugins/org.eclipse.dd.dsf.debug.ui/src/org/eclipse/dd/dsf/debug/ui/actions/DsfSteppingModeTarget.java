/*******************************************************************************
 * Copyright (c) 2008 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.ui.actions;

import org.eclipse.cdt.debug.core.model.ISteppingModeTarget;
import org.eclipse.cdt.debug.core.model.ITargetProperties;
import org.eclipse.core.runtime.Preferences;
import org.eclipse.core.runtime.Preferences.IPropertyChangeListener;
import org.eclipse.dd.dsf.debug.internal.ui.DsfDebugUIPlugin;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

/**
 */
public class DsfSteppingModeTarget implements ISteppingModeTarget, ITargetProperties {

	private static final String ID_DISASSEMBLY_VIEW= "org.eclipse.dd.dsf.debug.ui.disassembly.view"; //$NON-NLS-1$

	private final Preferences fPreferences;

	public DsfSteppingModeTarget() {
		fPreferences= new Preferences();
		fPreferences.setDefault(PREF_INSTRUCTION_STEPPING_MODE, false);
	}

	/*
	 * @see org.eclipse.cdt.debug.core.model.ISteppingModeTarget#enableInstructionStepping(boolean)
	 */
	public void enableInstructionStepping(boolean enabled) {
		fPreferences.setValue(PREF_INSTRUCTION_STEPPING_MODE, enabled);
		if (enabled) {
			try {
				final IWorkbenchWindow activeWorkbenchWindow= PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				if (activeWorkbenchWindow != null && activeWorkbenchWindow.getActivePage() != null) {
					activeWorkbenchWindow.getActivePage().showView(ID_DISASSEMBLY_VIEW);
				}
			} catch (PartInitException exc) {
				DsfDebugUIPlugin.log(exc);
			}
		}
	}

	/*
	 * @see org.eclipse.cdt.debug.core.model.ISteppingModeTarget#isInstructionSteppingEnabled()
	 */
	public boolean isInstructionSteppingEnabled() {
		return fPreferences.getBoolean(PREF_INSTRUCTION_STEPPING_MODE);
	}

	/*
	 * @see org.eclipse.cdt.debug.core.model.ISteppingModeTarget#supportsInstructionStepping()
	 */
	public boolean supportsInstructionStepping() {
		return true;
	}

	/*
	 * @see org.eclipse.cdt.debug.core.model.ITargetProperties#addPropertyChangeListener(org.eclipse.core.runtime.Preferences.IPropertyChangeListener)
	 */
	public void addPropertyChangeListener(IPropertyChangeListener listener) {
		fPreferences.addPropertyChangeListener(listener);
	}

	/*
	 * @see org.eclipse.cdt.debug.core.model.ITargetProperties#removePropertyChangeListener(org.eclipse.core.runtime.Preferences.IPropertyChangeListener)
	 */
	public void removePropertyChangeListener(IPropertyChangeListener listener) {
		fPreferences.removePropertyChangeListener(listener);
	}

}
