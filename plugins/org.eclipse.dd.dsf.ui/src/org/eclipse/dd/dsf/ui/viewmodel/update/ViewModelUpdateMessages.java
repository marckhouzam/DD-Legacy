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
package org.eclipse.dd.dsf.ui.viewmodel.update;

import org.eclipse.osgi.util.NLS;

public class ViewModelUpdateMessages extends NLS {

	private static final String BUNDLE_NAME = "org.eclipse.dd.dsf.ui.viewmodel.update.ViewModelUpdateMessages";//$NON-NLS-1$

    public static String AutomaticUpdatePolicy_name;
    public static String ManualUpdatePolicy_name;
    /** 
     * @since 1.1 
     */
    public static String AllUpdateScope_name;
    /** 
     * @since 1.1 
     */
    public static String VisibleUpdateScope_name;
	
    static {
        // load message values from bundle file
        NLS.initializeMessages(BUNDLE_NAME, ViewModelUpdateMessages.class);
    }
}
