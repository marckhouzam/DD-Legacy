/*******************************************************************************
 * Copyright (c) 2000, 2006 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     QNX Software Systems - Initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.mi.service.command.output;

/**
 * GDB/MI var-update.
 */

public class MIVarChange {
	String name;
	String value;
	boolean inScope;
	boolean changed;

	public MIVarChange(String n) {
		name = n;
	}

	public String getVarName() {
		return name;
	}

	public String getValue() {
		return value;
	}

	public boolean isInScope() {
		return inScope;
	}

	public boolean isChanged() {
		return changed;
	}

	public void setValue(String v) {
		value = v;
	}

	public void setInScope(boolean b) {
		inScope = b;
	}

	public void setChanged(boolean c) {
		changed = c;
	}
}
