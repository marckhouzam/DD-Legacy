/*******************************************************************************
 * Copyright (c) 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/

package org.eclipse.dd.dsf.debug.ui;

import org.eclipse.dd.dsf.debug.internal.ui.DsfDebugUIPlugin;
import org.eclipse.jface.preference.IPreferenceStore;

/**
 * @since 1.1
 */
public class DsfDebugUITools {
	
	public static IPreferenceStore getPreferenceStore()
	{
		return DsfDebugUIPlugin.getDefault().getPreferenceStore();
	}

}
