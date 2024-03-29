/*******************************************************************************
 * Copyright (c) 2008 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Anton Leherbauer (Wind River Systems) - initial API and implementation
 *******************************************************************************/

package org.eclipse.dd.dsf.debug.internal.ui.disassembly;

import java.math.BigInteger;

import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.ui.IWorkbenchPart;

/**
 * Interface which the disassembly view and editor implement.
 */
public interface IDisassemblyPart extends IWorkbenchPart {
	
	/**
	 * Property id for the active state of the part.
	 */
	public final int PROP_ACTIVE= 0x505;

	/**
	 * Property id for the connected state of the part.
	 */
	public final int PROP_CONNECTED= 0x506;

	/**
	 * Property id for the suspended state of the underlying execution context.
	 */
	public final int PROP_SUSPENDED= 0x507;
	
	/**
	 * Test whether this part is connected to a debug session and execution context.
	 * 
	 * @return <code>true</code> if the part is connected to a debug session and execution context
	 */
	boolean isConnected();
	
	/**
	 * Test whether this part is active. A part is active if it is visible and connected.
	 * 
	 * @return <code>true</code> if the part is active
	 */
	boolean isActive();
	
	/**
	 * Test whether the underlying execution context is currently suspended.
	 * Implies connected state.
	 * 
	 * @return <code>true</code> if the execution context is currently suspended
	 */
	boolean isSuspended();

	/**
	 * Get access to the text viewer.
	 * 
	 * @return the text viewer
	 */
	ISourceViewer getTextViewer();
	
	/**
	 * Navigate to the given address.
	 * 
	 * @param address
	 */
	void gotoAddress(BigInteger address);

	/**
	 * Navigate to current program counter.
	 */
	void gotoProgramCounter();

	/**
	 * Navigate to the address the given expression evaluates to.
	 * 
	 * @param expression  a symbolic address expression
	 */
	void gotoSymbol(String expression);

	/**
	 * Adds a ruler context menu listener to the disassembly part.
	 *
	 * @param listener the listener
	 */
	void addRulerContextMenuListener(IMenuListener listener);

	/**
	 * Removes a ruler context menu listener from the disassembly part.
	 *
	 * @param listener the listener
	 */
	void removeRulerContextMenuListener(IMenuListener listener);

}
