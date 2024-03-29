/*******************************************************************************
 * Copyright (c) 2007, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.internal.ui.disassembly.model;

import java.math.BigInteger;

/**
 * DisassemblyWithSourcePosition
 */
public class DisassemblyWithSourcePosition extends DisassemblyPosition {

	private String fFile;
	private int fLine;

	/**
	 * @param offset
	 * @param length
	 * @param addressOffset
	 * @param addressLength
	 * @param opcodes
	 */
	public DisassemblyWithSourcePosition(int offset, int length, BigInteger addressOffset, BigInteger addressLength, String opcodes, String file, int lineNr) {
		super(offset, length, addressOffset, addressLength, opcodes);
		fFile = file;
		fLine = lineNr;
	}

	@Override
	public String getFile() {
		return fFile;
	}

	@Override
	public int getLine() {
		return fLine;
	}
	
}
