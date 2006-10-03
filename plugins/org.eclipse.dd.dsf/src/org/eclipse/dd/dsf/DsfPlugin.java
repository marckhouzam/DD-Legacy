/*******************************************************************************
 * Copyright (c) 2006 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Plugin;
import org.osgi.framework.BundleContext;

/**
 * The activator class controls the plug-in life cycle
 */
public class DsfPlugin extends Plugin {

	// The plug-in ID
	public static final String PLUGIN_ID = "org.eclipse.dd.dsf";

	// The shared instance
	private static DsfPlugin fgPlugin;

    // BundleContext of this plugin
    private static BundleContext fgBundleContext; 

    // Debugging flag
    public static boolean DEBUG = false;

	/**
	 * The constructor
	 */
	public DsfPlugin() {
		fgPlugin = this;
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.core.runtime.Plugins#start(org.osgi.framework.BundleContext)
	 */
	public void start(BundleContext context) throws Exception {
        fgBundleContext = context;
		super.start(context);
        DEBUG = "true".equals(Platform.getDebugOption("org.eclipse.dd.dsf/debug"));  //$NON-NLS-1$//$NON-NLS-2$
    }

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.core.runtime.Plugin#stop(org.osgi.framework.BundleContext)
	 */
	public void stop(BundleContext context) throws Exception {
		fgPlugin = null;
        fgBundleContext = null;
		super.stop(context);
	}

	/**
	 * Returns the shared instance
	 *
	 * @return the shared instance
	 */
	public static DsfPlugin getDefault() {
		return fgPlugin;
	}

    public static BundleContext getBundleContext() {
        return fgBundleContext;
    }
    
    public static void debug(String message) {
        if (DEBUG) {
            System.out.println(message);
        }
    }


}
